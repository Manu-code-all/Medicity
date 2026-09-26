package com.medicity.jobs;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Deletes rows that can no longer affect anything.
 *
 * <ul>
 *   <li><b>Refresh tokens past their expiry.</b> Used and revoked tokens are
 *       kept until then on purpose: reuse detection needs to recognise a spent
 *       token when it comes back. Once expired, a token is refused for that
 *       reason alone, so the row has no job left.</li>
 *   <li><b>Idempotency keys older than a day.</b> {@code Idempotency} already
 *       ignores them.</li>
 * </ul>
 *
 * <p>Deleted in batches of {@value #BATCH} rows per statement. They share one
 * transaction, because the job's lock lives as long as the transaction; at
 * this system's volume (a few thousand rows a night) that is harmless. A much
 * larger backlog would call for a session-level lock and a commit per batch.
 */
@Component
@Slf4j
public class HousekeepingJob {

    static final int BATCH = 5_000;
    static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);

    private final JdbcTemplate jdbc;
    private final JobLock lock;
    private final MeterRegistry metrics;
    private final Clock clock;

    public HousekeepingJob(JdbcTemplate jdbc, JobLock lock, MeterRegistry metrics, Clock clock) {
        this.jdbc = jdbc;
        this.lock = lock;
        this.metrics = metrics;
        this.clock = clock;
    }

    public record Result(boolean ran, int refreshTokens, int idempotencyKeys) {}

    /** 03:30 UTC, when traffic is lowest. */
    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    @Transactional
    public Result run() {
        if (!lock.tryAcquire("housekeeping")) {
            log.info("Housekeeping skipped: another instance is running it");
            return new Result(false, 0, 0);
        }
        Instant now = clock.instant();
        int tokens = deleteInBatches("refresh_tokens", "expires_at < ?", now);
        int keys = deleteInBatches("idempotency_keys", "created_at < ?", now.minus(IDEMPOTENCY_RETENTION));

        metrics.counter("medicity.housekeeping.deleted", "table", "refresh_tokens").increment(tokens);
        metrics.counter("medicity.housekeeping.deleted", "table", "idempotency_keys").increment(keys);
        log.info("Housekeeping deleted {} expired refresh tokens and {} idempotency keys", tokens, keys);
        return new Result(true, tokens, keys);
    }

    private int deleteInBatches(String table, String condition, Instant cutoff) {
        // ctid (the row's physical address) lets DELETE take a LIMIT, which it
        // does not support directly. Table and condition are constants above.
        String sql = "DELETE FROM " + table + " WHERE ctid IN (SELECT ctid FROM " + table
                + " WHERE " + condition + " LIMIT " + BATCH + ")";
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.update(sql, Timestamp.from(cutoff));
            total += deleted;
        } while (deleted == BATCH);
        return total;
    }
}
