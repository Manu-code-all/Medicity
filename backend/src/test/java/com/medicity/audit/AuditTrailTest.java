package com.medicity.audit;

import com.medicity.clinical.PrescriptionRepository;
import com.medicity.common.ConflictException;
import com.medicity.doctor.Doctor;
import com.medicity.doctor.DoctorRepository;
import com.medicity.patient.Patient;
import com.medicity.patient.PatientRepository;
import com.medicity.scheduling.*;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The audit trail records the right events, in the right transaction, and
 * cannot be altered afterwards.
 *
 * <p>Rows are never cleaned up between tests, because the table refuses
 * deletion; that is one of the properties under test. Each test therefore
 * filters by the ids it created itself.
 */
@AutoConfigureMockMvc
@DisplayName("Audit trail")
class AuditTrailTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired PrescriptionRepository prescriptionRepository;
    @Autowired BookingService bookingService;
    @Autowired JwtService jwtService;
    @Autowired Clock clock;
    @Autowired AuditLog auditLog;

    private User aliceUser;
    private User malloryUser;
    private User doctorUser;
    private User adminUser;
    private Patient alice;
    private Patient mallory;
    private Doctor doctor;

    @BeforeEach
    void setUp() {
        prescriptionRepository.deleteAllInBatch();
        appointmentRepository.deleteAll();
        slotRepository.deleteAll();
        patientRepository.deleteAll();
        doctorRepository.deleteAll();
        userRepository.deleteAll();

        aliceUser = persistUser("Alice Menon", Role.PATIENT);
        alice = persistPatient(aliceUser);
        malloryUser = persistUser("Mallory Das", Role.PATIENT);
        mallory = persistPatient(malloryUser);
        doctorUser = persistUser("Dr. Anjali Rao", Role.DOCTOR);
        doctor = doctorRepository.save(Doctor.builder()
                .user(doctorUser)
                .specialization("Cardiology")
                .licenseNumber("LIC-" + UUID.randomUUID().toString().substring(0, 8))
                .consultationFee(new BigDecimal("900.00"))
                .yearsExperience(10)
                .build());
        adminUser = persistUser("Ops Admin", Role.ADMIN);
    }

    @Test
    @DisplayName("a booking is recorded with its actor, in the same transaction")
    void bookingIsRecorded() throws Exception {
        AppointmentSlot slot = persistSlot(2);

        String body = mvc.perform(post("/api/v1/appointments")
                        .header("Authorization", bearer(aliceUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slotId\":\"" + slot.getId() + "\",\"reason\":\"Check-up\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String appointmentId = body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        List<Map<String, Object>> rows = rowsFor(appointmentId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("action", "APPOINTMENT_BOOKED")
                .containsEntry("outcome", "SUCCESS")
                .containsEntry("actor_id", aliceUser.getId())
                .containsEntry("actor_role", "PATIENT");
        assertThat(rows.get(0).get("ip")).as("client address is captured").isNotNull();
    }

    @Test
    @DisplayName("a booking that loses the race leaves no 'booked' record")
    void failedBookingIsNotRecordedAsBooked() {
        AppointmentSlot slot = persistSlot(3);
        bookingService.book(slot.getId(), alice.getId(), "first");

        assertThatThrownBy(() -> bookingService.book(slot.getId(), mallory.getId(), "second"))
                .isInstanceOf(ConflictException.class);

        Integer booked = jdbc.queryForObject("""
                SELECT count(*) FROM audit_log
                WHERE action = 'APPOINTMENT_BOOKED' AND detail->>'slotId' = ?
                """, Integer.class, slot.getId().toString());
        assertThat(booked).isEqualTo(1);
    }

    @Test
    @DisplayName("a denied read is kept, although the request itself is rolled back")
    void denialSurvivesRollback() throws Exception {
        UUID appointmentId = bookingService.book(persistSlot(4).getId(), alice.getId(), "Private").getId();

        mvc.perform(get("/api/v1/appointments/" + appointmentId).header("Authorization", bearer(malloryUser)))
                .andExpect(status().isForbidden());

        assertThat(rowsFor(appointmentId.toString()))
                .anySatisfy(row -> assertThat(row)
                        .containsEntry("action", "ACCESS_DENIED")
                        .containsEntry("outcome", "DENIED")
                        .containsEntry("actor_id", malloryUser.getId()));
    }

    @Test
    @DisplayName("a doctor reading a patient's record is recorded; the patient reading their own is not")
    void thirdPartyReadsAreRecorded() throws Exception {
        UUID appointmentId = bookingService.book(persistSlot(5).getId(), alice.getId(), "Chest pain").getId();

        mvc.perform(get("/api/v1/appointments/" + appointmentId).header("Authorization", bearer(aliceUser)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/appointments/" + appointmentId).header("Authorization", bearer(doctorUser)))
                .andExpect(status().isOk());

        List<Map<String, Object>> views = rowsFor(appointmentId.toString()).stream()
                .filter(r -> "APPOINTMENT_VIEWED".equals(r.get("action")))
                .toList();
        assertThat(views).hasSize(1);
        assertThat(views.get(0)).containsEntry("actor_id", doctorUser.getId());
    }

    @Test
    @DisplayName("a denial on a long path is recorded (it used to overflow entity_id)")
    void longPathDenialIsRecorded() throws Exception {
        String path = "/api/v1/pharmacy/prescriptions/" + UUID.randomUUID() + "/dispense";

        mvc.perform(post(path).header("Authorization", bearer(aliceUser)))
                .andExpect(status().isForbidden());

        assertThat(rowsFor("POST " + path))
                .extracting(r -> r.get("action"), r -> r.get("outcome"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple("ACCESS_DENIED", "DENIED"));
    }

    @Test
    @DisplayName("an entity id longer than the column is cut to fit, not dropped")
    void overlongEntityIdIsTruncated() {
        String marker = "GET /" + UUID.randomUUID() + "/";
        auditLog.recordIndependently("ACCESS_DENIED", "ENDPOINT", marker + "a".repeat(500),
                AuditLog.Outcome.DENIED, null);

        String stored = jdbc.queryForObject("SELECT entity_id FROM audit_log WHERE entity_id LIKE ?",
                String.class, marker + "%");
        assertThat(stored).hasSize(AuditLog.ENTITY_ID_MAX).endsWith("…");
    }

    @Test
    @DisplayName("a failed login is recorded with the attempted email")
    void failedLoginIsRecorded() throws Exception {
        String email = "ghost-" + UUID.randomUUID() + "@medicity.test";

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"wrong-password-123\"}"))
                .andExpect(status().isUnauthorized());

        Integer failures = jdbc.queryForObject("""
                SELECT count(*) FROM audit_log
                WHERE action = 'LOGIN_FAILED' AND outcome = 'DENIED' AND detail->>'email' = ?
                """, Integer.class, email);
        assertThat(failures).isEqualTo(1);
    }

    @Test
    @DisplayName("the database refuses UPDATE, DELETE and TRUNCATE on the audit log")
    void auditLogIsImmutable() {
        bookingService.book(persistSlot(6).getId(), alice.getId(), "Anything");

        // Spring wraps the database error, so the trigger's message is in the
        // cause chain rather than on the outer exception.
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET outcome = 'DENIED'"))
                .hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log"))
                .hasStackTraceContaining("append-only");
        // Row-level triggers do not fire on TRUNCATE; V6 adds the statement-level one.
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE audit_log"))
                .hasStackTraceContaining("append-only");
    }

    @Test
    @DisplayName("only an admin can read the audit trail")
    void auditTrailIsAdminOnly() throws Exception {
        UUID appointmentId = bookingService.book(persistSlot(7).getId(), alice.getId(), "Anything").getId();

        mvc.perform(get("/api/v1/admin/audit").param("entityId", appointmentId.toString())
                        .header("Authorization", bearer(adminUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].action").value("APPOINTMENT_BOOKED"))
                .andExpect(jsonPath("$[0].detail.slotId").exists());

        mvc.perform(get("/api/v1/admin/audit").header("Authorization", bearer(aliceUser)))
                .andExpect(status().isForbidden());
    }

    // --- fixtures -------------------------------------------------------

    private List<Map<String, Object>> rowsFor(String entityId) {
        return jdbc.queryForList("""
                SELECT action, outcome, actor_id, actor_role, host(ip_address) AS ip
                FROM audit_log WHERE entity_id = ? ORDER BY id
                """, entityId);
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.issueAccessToken(user);
    }

    private AppointmentSlot persistSlot(int daysAhead) {
        Instant start = clock.instant().plus(daysAhead, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        return slotRepository.save(AppointmentSlot.builder()
                .doctor(doctor)
                .startsAt(start)
                .endsAt(start.plus(30, ChronoUnit.MINUTES))
                .status(SlotStatus.OPEN)
                .build());
    }

    private User persistUser(String name, Role role) {
        User user = User.builder()
                .passwordHash("{noop}irrelevant")
                .fullName(name)
                .role(role)
                .enabled(true)
                .build();
        user.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@medicity.test");
        return userRepository.save(user);
    }

    private Patient persistPatient(User user) {
        return patientRepository.save(Patient.builder()
                .user(user)
                .dateOfBirth(LocalDate.of(1993, 3, 21))
                .gender(Patient.Gender.UNDISCLOSED)
                .build());
    }
}
