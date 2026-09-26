package com.medicity.jobs;

import com.medicity.clinical.PrescriptionRepository;
import com.medicity.doctor.Doctor;
import com.medicity.doctor.DoctorRepository;
import com.medicity.patient.Patient;
import com.medicity.patient.PatientRepository;
import com.medicity.scheduling.Appointment;
import com.medicity.scheduling.AppointmentRepository;
import com.medicity.scheduling.AppointmentSlot;
import com.medicity.scheduling.AppointmentStatus;
import com.medicity.scheduling.SlotRepository;
import com.medicity.security.JwtService;
import com.medicity.support.AbstractIntegrationTest;
import com.medicity.user.Role;
import com.medicity.user.User;
import com.medicity.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("Scheduled jobs")
class ScheduledJobsTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired HousekeepingJob housekeeping;
    @Autowired UnclosedVisitsJob unclosedVisits;
    @Autowired UserRepository userRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired PrescriptionRepository prescriptionRepository;
    @Autowired JwtService jwtService;
    @Autowired Clock clock;

    private User doctorUser;
    private Doctor doctor;
    private Patient patient;
    private Instant now;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM prescription_dispensations");
        prescriptionRepository.deleteAllInBatch();
        appointmentRepository.deleteAll();
        slotRepository.deleteAll();
        patientRepository.deleteAll();
        doctorRepository.deleteAll();
        userRepository.deleteAll();
        jdbc.update("DELETE FROM idempotency_keys");

        now = clock.instant();
        doctorUser = persistUser(Role.DOCTOR);
        doctor = doctorRepository.save(Doctor.builder()
                .user(doctorUser)
                .specialization("Cardiology")
                .licenseNumber("LIC-" + UUID.randomUUID().toString().substring(0, 8))
                .consultationFee(new BigDecimal("900.00"))
                .yearsExperience(10)
                .build());
        patient = patientRepository.save(Patient.builder()
                .user(persistUser(Role.PATIENT))
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .gender(Patient.Gender.UNDISCLOSED)
                .build());
    }

    // --- unclosed visits ----------------------------------------------------

    @Test
    @DisplayName("a visit left open a day after it ended is marked missed and audited; recent ones are not")
    void unclosedVisitsAreMarkedMissed() {
        Appointment stale = visit(now.minus(26, ChronoUnit.HOURS));        // ended 25.5 h ago
        Appointment recent = visit(now.minus(3, ChronoUnit.HOURS));        // ended 2.5 h ago
        Appointment seen = visit(now.minus(30, ChronoUnit.HOURS));
        jdbc.update("UPDATE appointments SET status = 'COMPLETED' WHERE id = ?", seen.getId());

        assertThat(unclosedVisits.run()).isEqualTo(1);

        assertThat(statusOf(stale)).isEqualTo("NO_SHOW");
        assertThat(statusOf(recent)).isEqualTo("BOOKED");
        assertThat(statusOf(seen)).isEqualTo("COMPLETED");
        Integer audits = jdbc.queryForObject("""
                SELECT count(*) FROM audit_log
                WHERE action = 'VISIT_AUTO_CLOSED' AND entity_id = ? AND actor_id IS NULL
                """, Integer.class, stale.getId().toString());
        assertThat(audits).isEqualTo(1);

        // Running again changes nothing.
        assertThat(unclosedVisits.run()).isZero();
    }

    @Test
    @DisplayName("the doctor can still mark an automatically missed visit as seen")
    void doctorCanCorrectAnAutoClosedVisit() throws Exception {
        Appointment stale = visit(now.minus(26, ChronoUnit.HOURS));
        unclosedVisits.run();

        mvc.perform(post("/api/v1/doctors/me/visits/" + stale.getId() + "/complete")
                        .header("Authorization", "Bearer " + jwtService.issueAccessToken(doctorUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        String from = jdbc.queryForObject("""
                SELECT detail ->> 'from' FROM audit_log WHERE action = 'VISIT_COMPLETED' AND entity_id = ?
                """, String.class, stale.getId().toString());
        assertThat(from).isEqualTo("NO_SHOW");
    }

    @Test
    @DisplayName("the job bumps the version, so a doctor's stale save loses with a conflict instead of overwriting")
    void autoCloseBumpsTheVersion() {
        Appointment stale = visit(now.minus(26, ChronoUnit.HOURS));
        Long before = jdbc.queryForObject("SELECT version FROM appointments WHERE id = ?", Long.class, stale.getId());

        unclosedVisits.run();

        Long after = jdbc.queryForObject("SELECT version FROM appointments WHERE id = ?", Long.class, stale.getId());
        assertThat(after).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("while one instance holds a job's lock, another skips the run")
    void onlyOneInstanceRunsAJob() throws Exception {
        visit(now.minus(26, ChronoUnit.HOURS));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // "Instance A": takes the lock in its own transaction and holds it.
        CompletableFuture<Void> instanceA = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtext('medicity.job.unclosed-visits'))", Boolean.class);
            locked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        // "Instance B" skips while A holds it...
        assertThat(unclosedVisits.run()).isZero();

        // ...and runs once A is done.
        release.countDown();
        instanceA.get(10, TimeUnit.SECONDS);
        assertThat(unclosedVisits.run()).isEqualTo(1);
    }

    // --- housekeeping -------------------------------------------------------

    @Test
    @DisplayName("housekeeping deletes expired refresh tokens and day-old idempotency keys, and nothing else")
    void housekeepingDeletesOnlyDeadRows() {
        UUID user = patient.getUser().getId();
        UUID family = UUID.randomUUID();
        insertRefreshToken(user, family, now.minus(8, ChronoUnit.DAYS), now.minus(1, ChronoUnit.HOURS), true);
        // Spent but not yet expired: kept, because reuse detection still needs it.
        insertRefreshToken(user, family, now.minus(1, ChronoUnit.DAYS), now.plus(6, ChronoUnit.DAYS), true);
        insertRefreshToken(user, UUID.randomUUID(), now, now.plus(7, ChronoUnit.DAYS), false);
        insertIdempotencyKey(user, "old", now.minus(25, ChronoUnit.HOURS));
        insertIdempotencyKey(user, "fresh", now.minus(1, ChronoUnit.HOURS));

        HousekeepingJob.Result result = housekeeping.run();

        assertThat(result.ran()).isTrue();
        assertThat(result.refreshTokens()).isEqualTo(1);
        assertThat(result.idempotencyKeys()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, user))
                .isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT idempotency_key FROM idempotency_keys WHERE user_id = ?", String.class, user))
                .containsExactly("fresh");
    }

    // --- fixtures -------------------------------------------------------------

    private Appointment visit(Instant start) {
        AppointmentSlot slot = slotRepository.save(AppointmentSlot.builder()
                .doctor(doctor).startsAt(start).endsAt(start.plus(30, ChronoUnit.MINUTES)).build());
        return appointmentRepository.save(Appointment.builder()
                .slot(slot).patient(patient).status(AppointmentStatus.BOOKED).reason("Check-up").scheduledAt(start).build());
    }

    private String statusOf(Appointment a) {
        return jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, a.getId());
    }

    private void insertRefreshToken(UUID user, UUID family, Instant issued, Instant expires, boolean used) {
        byte[] hash = new byte[32];
        new java.security.SecureRandom().nextBytes(hash);
        jdbc.update("""
                INSERT INTO refresh_tokens (user_id, family_id, token_hash, issued_at, expires_at, used_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, user, family, hash, Timestamp.from(issued), Timestamp.from(expires),
                used ? Timestamp.from(issued) : null);
    }

    private void insertIdempotencyKey(UUID user, String key, Instant created) {
        jdbc.update("""
                INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash, response_status, response_body, created_at)
                VALUES (?, ?, ?, 201, '{}'::jsonb, ?)
                """, user, key, new byte[32], Timestamp.from(created));
    }

    private User persistUser(Role role) {
        User user = User.builder()
                .passwordHash("{noop}irrelevant")
                .fullName("Test " + role.name().toLowerCase())
                .role(role)
                .enabled(true)
                .build();
        user.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@medicity.test");
        return userRepository.save(user);
    }
}
