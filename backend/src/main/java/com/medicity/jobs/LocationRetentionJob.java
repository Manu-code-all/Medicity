package com.medicity.jobs;

import com.medicity.audit.AuditLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * Clears, or coarsens, the place a medicine question was asked from once the
 * question has been closed for {@code medicity.retention.request-location.after}.
 *
 * <p>Data minimisation (DPDP Act): the latitude and longitude on
 * {@code medicine_requests} exist so stores can be found within a radius at
 * the moment of asking. The distance each store was given is stored beside the
 * store (request_recipients), and nothing else reads the coordinates, so
 * keeping a precise home-area trail of someone's prescriptions for ever buys
 * nothing and exposes a lot.
 *
 * <p>Only questions that are finished are touched ({@code CLOSED} or
 * {@code EXPIRED}); an open one still needs its place. Each row is handled
 * once: {@code location_minimised_at} is set on it, which also takes it out of
 * the partial index (V41) that makes the nightly scan cheap, and means
 * "coarsen" is never applied to its own output.
 *
 * <p>Rows are handled in batches in one transaction, like
 * {@link HousekeepingJob}, whose reasoning about the job lock applies here.
 */
@Component
@Slf4j
public class LocationRetentionJob {

    static final int BATCH = 5_000;

    public enum Mode { CLEAR, COARSEN }

    private final JdbcTemplate jdbc;
    private final JobLock lock;
    private final AuditLog auditLog;
    private final Clock clock;
    private final Duration after;
    private final Mode mode;
    private final int decimals;

    public LocationRetentionJob(JdbcTemplate jdbc, JobLock lock, AuditLog auditLog, Clock clock,
                                @Value("${medicity.retention.request-location.after}") Duration after,
                                @Value("${medicity.retention.request-location.mode}") String mode,
                                @Value("${medicity.retention.request-location.coarsen-decimals}") int decimals) {
        this.jdbc = jdbc;
        this.lock = lock;
        this.auditLog = auditLog;
        this.clock = clock;
        if (after.isNegative() || after.isZero()) {
            throw new IllegalArgumentException("medicity.retention.request-location.after must be positive");
        }
        this.after = after;
        this.mode = Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        // Four decimals is about 11 m: no coarser than a doorstep, so not a retention setting at all.
        if (decimals < 0 || decimals > 3) {
            throw new IllegalArgumentException("medicity.retention.request-location.coarsen-decimals must be 0 to 3");
        }
        this.decimals = decimals;
    }

    /** 03:45 UTC, after housekeeping. @return questions handled. */
    @Scheduled(cron = "0 45 3 * * *", zone = "UTC")
    @Transactional
    public int run() {
        if (!lock.tryAcquire("location-retention")) {
            log.info("Location retention skipped: another instance is running it");
            return 0;
        }
        Instant now = clock.instant();
        Timestamp cutoff = Timestamp.from(now.minus(after));
        Timestamp stamp = Timestamp.from(now);

        // `decimals` is an int checked at construction, so formatting it into the
        // statement cannot inject anything; the cutoff and stamp are bound.
        String set = mode == Mode.CLEAR
                ? "latitude = NULL, longitude = NULL"
                : "latitude = round(latitude, %d), longitude = round(longitude, %d)".formatted(decimals, decimals);
        String sql = """
                UPDATE medicine_requests SET %s, location_minimised_at = ?
                WHERE id IN (SELECT id FROM medicine_requests
                             WHERE status IN ('CLOSED', 'EXPIRED') AND closed_at < ?
                               AND location_minimised_at IS NULL
                             LIMIT %d)
                """.formatted(set, BATCH);

        int total = 0;
        int updated;
        do {
            updated = jdbc.update(sql, stamp, cutoff);
            total += updated;
        } while (updated == BATCH);

        if (total > 0) {
            auditLog.recordChange("LOCATION_RETENTION_APPLIED", "MEDICINE_REQUEST", null,
                    Map.of("mode", mode.name(), "questions", total, "olderThan", after.toString()));
            log.info("Location retention ({}) applied to {} closed medicine question(s)", mode, total);
        }
        return total;
    }
}
