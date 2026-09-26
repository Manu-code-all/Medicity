package com.medicity.outbox;

import com.medicity.clinical.PrescribingService;
import com.medicity.clinical.PrescriptionRepository;
import com.medicity.clinical.VisitService;
import com.medicity.common.ConflictException;
import com.medicity.doctor.Doctor;
import com.medicity.doctor.DoctorRepository;
import com.medicity.pharmacy.Medicine;
import com.medicity.pharmacy.MedicineRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("Outbox and notifications")
class OutboxNotificationsTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxRelay relay;
    @Autowired BookingService bookingService;
    @Autowired VisitService visitService;
    @Autowired PrescribingService prescribingService;
    @Autowired UserRepository userRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired PrescriptionRepository prescriptionRepository;
    @Autowired MedicineRepository medicineRepository;
    @Autowired JwtService jwtService;
    @Autowired Clock clock;

    private User patientUser;
    private Patient patient;
    private User otherUser;
    private Patient other;
    private User doctorUser;
    private Doctor doctor;
    private User adminUser;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM prescription_dispensations");
        prescriptionRepository.deleteAllInBatch();
        appointmentRepository.deleteAll();
        slotRepository.deleteAll();
        patientRepository.deleteAll();
        doctorRepository.deleteAll();
        userRepository.deleteAll();
        jdbc.update("DELETE FROM medicines WHERE generic_name LIKE 'ob-test-%'");
        // Other test classes publish events without draining them, for users
        // since deleted. Left here, they would fill the relay's batch.
        jdbc.update("DELETE FROM outbox_events");

        patientUser = persistUser(Role.PATIENT, "Meera Nair");
        patient = persistPatient(patientUser);
        otherUser = persistUser(Role.PATIENT, "Mallory Das");
        other = persistPatient(otherUser);
        doctorUser = persistUser(Role.DOCTOR, "Dr. Anjali Rao");
        doctor = doctorRepository.save(Doctor.builder()
                .user(doctorUser).specialization("Cardiology")
                .licenseNumber("LIC-" + UUID.randomUUID().toString().substring(0, 8))
                .consultationFee(new BigDecimal("900.00")).yearsExperience(10).build());
        adminUser = persistUser(Role.ADMIN, "Ops Admin");
    }

    @Test
    @DisplayName("a booking writes an event; the relay turns it into the patient's notification")
    void bookingNotifiesThePatient() throws Exception {
        Appointment booked = bookingService.book(slotIn(2).getId(), patient.getId(), "Check-up");
        assertThat(pending()).isEqualTo(1);

        assertThat(relay.drain()).isEqualTo(1);

        assertThat(pending()).isZero();
        mvc.perform(get("/api/v1/notifications").header("Authorization", bearer(patientUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Appointment confirmed"))
                .andExpect(jsonPath("$.items[0].body").value("With Dr. Anjali Rao."))
                .andExpect(jsonPath("$.items[0].occursAt").value(booked.getScheduledAt().toString()))
                .andExpect(jsonPath("$.items[0].link").value("/portal/visits"));
    }

    @Test
    @DisplayName("a booking that fails writes no event: the event rolls back with it")
    void failedBookingWritesNoEvent() {
        AppointmentSlot slot = slotIn(2);
        bookingService.book(slot.getId(), patient.getId(), "First");
        int before = total();

        assertThatThrownBy(() -> bookingService.book(slot.getId(), other.getId(), "Too late"))
                .isInstanceOf(ConflictException.class);

        assertThat(total()).isEqualTo(before);
    }

    @Test
    @DisplayName("a cancellation tells the doctor")
    void cancellationNotifiesTheDoctor() throws Exception {
        Appointment booked = bookingService.book(slotIn(2).getId(), patient.getId(), "Check-up");
        bookingService.cancel(booked.getId(), "Feeling better");

        relay.drain();

        mvc.perform(get("/api/v1/notifications").header("Authorization", bearer(doctorUser)))
                .andExpect(jsonPath("$.items[0].title").value("Visit cancelled"))
                .andExpect(jsonPath("$.items[0].body").value("Meera Nair cancelled."));
    }

    @Test
    @DisplayName("delivering an event twice leaves one notification (at-least-once, idempotent consumer)")
    void redeliveryIsHarmless() {
        bookingService.book(slotIn(2).getId(), patient.getId(), "Check-up");
        relay.drain();

        // As if the relay crashed after delivering but before marking it published.
        jdbc.update("UPDATE outbox_events SET published_at = NULL");
        assertThat(relay.drain()).isEqualTo(1);

        assertThat(notificationsFor(patientUser)).isEqualTo(1);
    }

    @Test
    @DisplayName("a failing event is retried with backoff, then set aside after 10 attempts")
    void failingEventIsRetriedThenSetAside() {
        jdbc.update("INSERT INTO outbox_events (event_type, aggregate_id, payload) VALUES ('NO_SUCH_EVENT', ?, '{}')",
                UUID.randomUUID());

        assertThat(relay.drain()).isZero();
        var row = jdbc.queryForMap("SELECT attempts, last_error, next_attempt_at, failed_at, published_at FROM outbox_events");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat((String) row.get("last_error")).contains("No consumer for event type NO_SUCH_EVENT");
        assertThat(((java.sql.Timestamp) row.get("next_attempt_at")).toInstant()).isAfter(clock.instant());
        assertThat(row.get("failed_at")).isNull();
        assertThat(row.get("published_at")).isNull();

        // Not due yet: another drain leaves it alone.
        relay.drain();
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_events", Integer.class)).isEqualTo(1);

        jdbc.update("UPDATE outbox_events SET attempts = 9, next_attempt_at = now() - interval '1 second'");
        relay.drain();
        assertThat(jdbc.queryForObject("SELECT failed_at IS NOT NULL FROM outbox_events", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("four relays draining at once deliver each of 40 events exactly once (SKIP LOCKED)")
    void concurrentRelaysShareTheWork() throws Exception {
        for (int i = 0; i < 40; i++) {
            jdbc.update("""
                    INSERT INTO outbox_events (event_type, aggregate_id, payload)
                    VALUES ('APPOINTMENT_BOOKED', ?, CAST(? AS jsonb))
                    """, UUID.randomUUID(), """
                    {"patientUserId":"%s","doctorName":"Dr. Anjali Rao","scheduledAt":"2030-01-01T09:00:00Z"}
                    """.formatted(patientUser.getId()));
        }

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> relays = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            relays.add(pool.submit(() -> {
                start.await();
                return relay.drain();
            }));
        }
        start.countDown();
        int delivered = 0;
        for (Future<Integer> r : relays) delivered += r.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(delivered).isEqualTo(40);
        assertThat(pending()).isZero();
        assertThat(notificationsFor(patientUser)).isEqualTo(40);
    }

    @Test
    @DisplayName("correcting a dispensed prescription warns the patient and the pharmacy (admins)")
    void correctionAfterDispensingWarnsPatientAndPharmacy() throws Exception {
        Instant start = clock.instant().minus(1, ChronoUnit.HOURS);
        AppointmentSlot slot = slotRepository.save(AppointmentSlot.builder()
                .doctor(doctor).startsAt(start).endsAt(start.plus(30, ChronoUnit.MINUTES)).build());
        Appointment visit = appointmentRepository.save(Appointment.builder()
                .slot(slot).patient(patient).status(AppointmentStatus.BOOKED).reason("Fever").scheduledAt(start).build());
        visitService.complete(visit.getId(), doctor.getId());
        Medicine paracetamol = medicineRepository.save(Medicine.builder()
                .name("Paracetamol-" + UUID.randomUUID().toString().substring(0, 6)).genericName("ob-test-para")
                .form(Medicine.Form.TABLET).strength("500mg").unitPrice(new BigDecimal("40.00")).build());
        var draft = new PrescribingService.PrescriptionDraft("Viral fever", null,
                List.of(new PrescribingService.PrescriptionDraft.Item(paracetamol.getId(), "1 tablet", "Three times a day", 3, 9)));
        var original = prescribingService.issue(visit.getId(), doctor.getId(), draft);
        jdbc.update("INSERT INTO prescription_dispensations (prescription_id, dispensed_by) VALUES (?, ?)",
                original.getId(), adminUser.getId());

        prescribingService.correct(original.getId(), doctor.getId(), draft);
        relay.drain();

        mvc.perform(get("/api/v1/notifications").header("Authorization", bearer(patientUser)))
                .andExpect(jsonPath("$.items[0].title").value("Prescription corrected"))
                .andExpect(jsonPath("$.items[1].title").value("New prescription"));
        mvc.perform(get("/api/v1/notifications").header("Authorization", bearer(adminUser)))
                .andExpect(jsonPath("$.items[0].title").value("Dispensed prescription was corrected"));
    }

    @Test
    @DisplayName("users see and mark only their own notifications")
    void notificationsAreScopedToTheirOwner() throws Exception {
        bookingService.book(slotIn(2).getId(), patient.getId(), "Check-up");
        relay.drain();
        UUID mine = jdbc.queryForObject("SELECT id FROM notifications WHERE user_id = ?", UUID.class, patientUser.getId());

        mvc.perform(get("/api/v1/notifications").header("Authorization", bearer(otherUser)))
                .andExpect(jsonPath("$.unread").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(post("/api/v1/notifications/" + mine + "/read").header("Authorization", bearer(otherUser)))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/notifications/" + mine + "/read").header("Authorization", bearer(patientUser)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/notifications").header("Authorization", bearer(patientUser)))
                .andExpect(jsonPath("$.unread").value(0))
                .andExpect(jsonPath("$.items[0].read").value(true));
    }

    // --- helpers --------------------------------------------------------

    private int pending() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Integer.class);
    }

    private int total() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class);
    }

    private int notificationsFor(User user) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ?", Integer.class, user.getId());
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.issueAccessToken(user);
    }

    private AppointmentSlot slotIn(int days) {
        Instant start = clock.instant().plus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        return slotRepository.save(AppointmentSlot.builder()
                .doctor(doctor).startsAt(start).endsAt(start.plus(30, ChronoUnit.MINUTES))
                .status(SlotStatus.OPEN).build());
    }

    private User persistUser(Role role, String name) {
        User user = User.builder()
                .passwordHash("{noop}irrelevant").fullName(name).role(role).enabled(true).build();
        user.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@medicity.test");
        return userRepository.save(user);
    }

    private Patient persistPatient(User user) {
        return patientRepository.save(Patient.builder()
                .user(user).dateOfBirth(LocalDate.of(1990, 1, 1)).gender(Patient.Gender.UNDISCLOSED).build());
    }
}
