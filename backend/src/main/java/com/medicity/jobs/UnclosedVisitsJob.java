package com.medicity.jobs;

import com.medicity.audit.AuditLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Marks visits nobody closed as missed, a day after they ended.
 *
 * <p>Without this, a visit the doctor never closed stays {@code BOOKED}
 * forever: the patient's history shows an appointment that neither happened
 * nor was missed, and no one is prompted to fix it.
 *
 * <p>A day's grace because the doctor is the better source: most visits are
 * closed within hours. If the job guesses wrong (the patient was seen and the
 * doctor forgot), the doctor can still mark the visit seen; see
 * {@code VisitService}. Every change is audited as {@code VISIT_AUTO_CLOSED}
 * with no actor, so it is never mistaken for a doctor's decision.
 *
 * <p>One {@code UPDATE}, not load-then-save: the {@code status = 'BOOKED'}
 * condition is re-checked on each row under its lock, so a visit the doctor
 * closes at the same moment is either closed by the doctor (and skipped here)
 * or by this job (and the doctor's save fails its version check with 409).
 * The version is bumped for exactly that reason.
 */
@Component
@Slf4j
public class UnclosedVisitsJob {

    static final Duration GRACE = Duration.ofHours(24);

    private final JdbcTemplate jdbc;
    private final JobLock lock;
    private final AuditLog auditLog;
    private final Clock clock;

    public UnclosedVisitsJob(JdbcTemplate jdbc, JobLock lock, AuditLog auditLog, Clock clock) {
        this.jdbc = jdbc;
        this.lock = lock;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /** Hourly, at a minute past, so a visit is closed at most an hour after its grace ends. */
    @Scheduled(cron = "0 1 * * * *", zone = "UTC")
    @Transactional
    public int run() {
        if (!lock.tryAcquire("unclosed-visits")) {
            return 0;
        }
        List<UUID> closed = jdbc.queryForList("""
                UPDATE appointments a
                SET status = 'NO_SHOW', version = a.version + 1, updated_at = ?
                FROM appointment_slots s
                WHERE s.id = a.slot_id AND a.status = 'BOOKED' AND s.ends_at < ?
                RETURNING a.id
                """, UUID.class, Timestamp.from(clock.instant()), Timestamp.from(clock.instant().minus(GRACE)));

        // Same transaction as the update: the visits and their audit rows
        // commit together or not at all.
        for (UUID id : closed) {
            auditLog.recordChange("VISIT_AUTO_CLOSED", "APPOINTMENT", id,
                    Map.of("reason", "Not closed by the doctor within %d hours of its end".formatted(GRACE.toHours())));
        }
        if (!closed.isEmpty()) {
            log.info("Marked {} unclosed visit(s) as missed", closed.size());
        }
        return closed.size();
    }
}
