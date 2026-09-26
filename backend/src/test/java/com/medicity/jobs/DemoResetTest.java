package com.medicity.jobs;

import com.medicity.audit.AuditLog;
import com.medicity.clinical.PrescriptionRepository;
import com.medicity.doctor.DoctorRepository;
import com.medicity.patient.PatientRepository;
import com.medicity.scheduling.AppointmentRepository;
import com.medicity.scheduling.SlotRepository;
import com.medicity.support.AbstractIntegrationTest;
import com.medicity.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The nightly demo reset. The job is a {@code demo}-profile bean, so it is
 * built by hand here: running this class under that profile would load the
 * demo seed into the database every other test class shares.
 */
@DisplayName("Demo reset")
class DemoResetTest extends AbstractIntegrationTest {

    private static final String MEERA = "bbbbbbbb-2222-4222-8222-bbbbbbbbbb01";
    private static final String ARJUN_VISIT = "eeeeeeee-5555-4555-8555-eeeeeeeeee07";
    private static final String RAO = "aaaaaaaa-1111-4111-8111-aaaaaaaaaa01";
    private static final String OMEPRAZOLE = "cccccccc-3333-4333-8333-cccccccccc06";

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired JobLock jobLock;
    @Autowired AuditLog auditLog;
    @Autowired TransactionTemplate transactions;
    @Autowired UserRepository userRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired PrescriptionRepository prescriptionRepository;

    private DemoResetJob job;

    @BeforeEach
    void setUp() {
        cleanUp();
        job = new DemoResetJob(jdbc, dataSource, jobLock, auditLog);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    @DisplayName("seeds the demo world: Meera's history, Dr. Rao's day, stock")
    void seedsTheDemo() {
        reset();

        assertThat(count("SELECT count(*) FROM appointments WHERE patient_id = '" + MEERA + "'")).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM prescriptions WHERE patient_id = '" + MEERA + "'")).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM prescription_dispensations")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = '" + ARJUN_VISIT + "'", String.class))
                .isEqualTo("BOOKED");
        assertThat(count("SELECT quantity_on_hand FROM medicine_stock WHERE medicine_id = '" + OMEPRAZOLE + "'"))
                .isEqualTo(250);
    }

    @Test
    @DisplayName("undoes what visitors did, keeps their accounts, and resets stock through the ledger")
    void undoesVisitorChanges() {
        reset();
        // A day of visitors: the waiting visit closed, a new account booking a
        // demo slot, stock dispensed, a notification delivered.
        jdbc.update("UPDATE appointments SET status = 'COMPLETED' WHERE id = '" + ARJUN_VISIT + "'");
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, role)
                VALUES ('99999999-9999-4999-8999-999999999901', 'visitor@example.test', 'x', 'A Visitor', 'PATIENT')
                """);
        jdbc.update("""
                INSERT INTO patients (id, user_id, date_of_birth, gender)
                VALUES ('99999999-9999-4999-8999-999999999902', '99999999-9999-4999-8999-999999999901', '1990-01-01', 'OTHER')
                """);
        jdbc.update("""
                INSERT INTO appointments (slot_id, patient_id, status, scheduled_at)
                SELECT s.id, '99999999-9999-4999-8999-999999999902', 'BOOKED', s.starts_at
                FROM appointment_slots s
                WHERE s.doctor_id = '%s' AND s.starts_at > now() + interval '3 days'
                  AND NOT EXISTS (SELECT 1 FROM appointments a WHERE a.slot_id = s.id)
                ORDER BY s.starts_at LIMIT 1
                """.formatted(RAO));
        jdbc.update("UPDATE medicine_stock SET quantity_on_hand = 240 WHERE medicine_id = '" + OMEPRAZOLE + "'");
        jdbc.update("""
                INSERT INTO notifications (user_id, event_id, kind, title, body)
                VALUES ('99999999-9999-4999-8999-999999999901', gen_random_uuid(), 'X', 't', 'b')
                """);

        reset();

        assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = '" + ARJUN_VISIT + "'", String.class))
                .isEqualTo("BOOKED");
        assertThat(count("SELECT count(*) FROM appointments WHERE patient_id = '99999999-9999-4999-8999-999999999902'"))
                .isZero();
        assertThat(count("SELECT count(*) FROM users WHERE email = 'visitor@example.test'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notifications")).isZero();
        assertThat(count("SELECT quantity_on_hand FROM medicine_stock WHERE medicine_id = '" + OMEPRAZOLE + "'"))
                .isEqualTo(250);
        assertThat(count("""
                SELECT delta FROM stock_movements
                WHERE medicine_id = '%s' AND reason = 'ADJUSTMENT' ORDER BY id DESC LIMIT 1
                """.formatted(OMEPRAZOLE))).isEqualTo(10);
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'DEMO_RESET'")).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("if re-seeding fails, nothing is deleted: the demo is never left empty")
    void resetIsAllOrNothing() {
        reset();
        int before = count("SELECT count(*) FROM appointments");
        DemoResetJob broken = new DemoResetJob(jdbc, dataSource, jobLock, auditLog,
                new ByteArrayResource("SELECT 1 / 0;".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> transactions.execute(tx -> broken.run()));

        assertThat(count("SELECT count(*) FROM appointments")).isEqualTo(before);
    }

    // --- helpers --------------------------------------------------------

    /** The job's own @Transactional needs a proxy; here the transaction is opened explicitly. */
    private void reset() {
        Boolean ran = transactions.execute(tx -> job.run());
        assertThat(ran).isTrue();
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }

    private void cleanUp() {
        jdbc.update("DELETE FROM prescription_dispensations");
        prescriptionRepository.deleteAllInBatch();
        appointmentRepository.deleteAll();
        slotRepository.deleteAll();
        patientRepository.deleteAll();
        doctorRepository.deleteAll();
        userRepository.deleteAll();
        jdbc.update("DELETE FROM notifications");
        // Other test classes create medicines with the demo's names under
        // their own ids (StockLedgerConcurrencyTest's "Paracetamol 500mg").
        // The seed's ON CONFLICT DO NOTHING would then skip the demo's row and
        // its prescriptions would reference a medicine that is not there.
        String clashing = """
                SELECT id FROM medicines
                WHERE id::text NOT LIKE 'cccccccc-3333-4333-8333-%'
                  AND (lower(name), coalesce(strength, '')) IN (('paracetamol', '500mg'), ('azithromycin', '250mg'),
                      ('cetirizine', '10mg'), ('amoxicillin', '500mg'), ('metformin', '500mg'), ('omeprazole', '20mg'))
                """;
        jdbc.update("DELETE FROM stock_movements WHERE medicine_id IN (" + clashing + ")");
        jdbc.update("DELETE FROM prescription_items WHERE medicine_id IN (" + clashing + ")");
        jdbc.update("DELETE FROM medicines WHERE id IN (" + clashing + ")");
    }
}
