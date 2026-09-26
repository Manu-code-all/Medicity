package com.medicity.jobs;

import com.medicity.audit.AuditLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

/**
 * Puts the public demo back to its starting state every night.
 *
 * <p>The demo is shared: the first visitor who closes Dr. Rao's waiting visit
 * or books the open slots changes it for everyone after, and its "today"
 * visits drift into the past. This deletes the demo's clinical data (every
 * appointment with a demo doctor or for a demo patient, and what hangs off
 * them) and re-runs the demo seed, whose dates are relative to now.
 *
 * <p>Only under the {@code demo} profile, the same switch that loads the seed,
 * so a real deployment never has this bean. Accounts visitors registered are
 * kept; their bookings with demo doctors are removed with everything else.
 *
 * <p>One transaction: if re-seeding fails, the deletions roll back too and the
 * demo stays as it was rather than empty.
 */
@Component
@Profile("demo")
@Slf4j
public class DemoResetJob {

    static final Resource SEED = new ClassPathResource("db/seed/R__demo_data.sql");

    /** Fixed ids from the seed. */
    private static final String DEMO_DOCTORS = """
            ('aaaaaaaa-1111-4111-8111-aaaaaaaaaa01', 'aaaaaaaa-1111-4111-8111-aaaaaaaaaa02',
             'aaaaaaaa-1111-4111-8111-aaaaaaaaaa03')""";
    private static final String DEMO_PATIENTS = """
            ('bbbbbbbb-2222-4222-8222-bbbbbbbbbb01', 'bbbbbbbb-2222-4222-8222-bbbbbbbbbb02',
             'bbbbbbbb-2222-4222-8222-bbbbbbbbbb03')""";
    private static final String DEMO_MEDICINES = "(SELECT id FROM medicines WHERE id::text LIKE 'cccccccc-3333-4333-8333-%')";
    private static final int SEED_STOCK = 250;

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final JobLock lock;
    private final AuditLog auditLog;
    private final Resource seed;

    public DemoResetJob(JdbcTemplate jdbc, DataSource dataSource, JobLock lock, AuditLog auditLog) {
        this(jdbc, dataSource, lock, auditLog, SEED);
    }

    /** With another seed script: tests use a failing one to prove the reset is all-or-nothing. */
    DemoResetJob(JdbcTemplate jdbc, DataSource dataSource, JobLock lock, AuditLog auditLog, Resource seed) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.lock = lock;
        this.auditLog = auditLog;
        this.seed = seed;
    }

    /** 02:30 UTC, 08:00 in India, before the day's visitors. */
    @Scheduled(cron = "0 30 2 * * *", zone = "UTC")
    @Transactional
    public boolean run() {
        if (!lock.tryAcquire("demo-reset")) {
            return false;
        }
        int appointments = wipe();
        reseed();
        auditLog.recordChange("DEMO_RESET", "SYSTEM", null, Map.of("appointmentsRemoved", appointments));
        log.info("Demo reset: removed {} appointments and re-seeded", appointments);
        return true;
    }

    /** Deletes the demo's clinical data, children first. @return appointments removed. */
    int wipe() {
        jdbc.execute("""
                CREATE TEMP TABLE demo_appointments ON COMMIT DROP AS
                SELECT a.id FROM appointments a JOIN appointment_slots s ON s.id = a.slot_id
                WHERE s.doctor_id IN %s OR a.patient_id IN %s
                """.formatted(DEMO_DOCTORS, DEMO_PATIENTS));
        jdbc.update("""
                DELETE FROM prescription_dispensations WHERE prescription_id IN
                  (SELECT id FROM prescriptions WHERE appointment_id IN (SELECT id FROM demo_appointments))
                """);
        // Items cascade. Originals and their corrections go in one statement,
        // so the self-reference is satisfied when the statement ends.
        jdbc.update("DELETE FROM prescriptions WHERE appointment_id IN (SELECT id FROM demo_appointments)");
        int appointments = jdbc.update("DELETE FROM appointments WHERE id IN (SELECT id FROM demo_appointments)");
        jdbc.update("DELETE FROM appointment_slots WHERE doctor_id IN " + DEMO_DOCTORS);
        jdbc.execute("DROP TABLE demo_appointments");

        // Notifications and stored idempotent responses describe bookings that
        // no longer exist. On the demo everything is demo activity.
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM idempotency_keys");

        // Stock back to the seed level, through the ledger so on-hand stays
        // reconstructable from movements.
        jdbc.update("""
                INSERT INTO stock_movements (medicine_id, delta, reason)
                SELECT medicine_id, ? - quantity_on_hand, 'ADJUSTMENT' FROM medicine_stock
                WHERE medicine_id IN %s AND quantity_on_hand <> ?
                """.formatted(DEMO_MEDICINES), SEED_STOCK, SEED_STOCK);
        jdbc.update("""
                UPDATE medicine_stock SET quantity_on_hand = ?, updated_at = now(), version = version + 1
                WHERE medicine_id IN %s AND quantity_on_hand <> ?
                """.formatted(DEMO_MEDICINES), SEED_STOCK, SEED_STOCK);
        return appointments;
    }

    private void reseed() {
        // The transaction's own connection, so the seed commits or rolls back
        // with the deletions above. Released, not closed: the transaction owns it.
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            ScriptUtils.executeSqlScript(connection, seed);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }
}
