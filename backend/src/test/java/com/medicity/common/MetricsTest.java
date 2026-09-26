package com.medicity.common;

import com.medicity.clinical.PrescriptionRepository;
import com.medicity.doctor.Doctor;
import com.medicity.doctor.DoctorRepository;
import com.medicity.patient.Patient;
import com.medicity.patient.PatientRepository;
import com.medicity.scheduling.AppointmentRepository;
import com.medicity.scheduling.AppointmentSlot;
import com.medicity.scheduling.BookingService;
import com.medicity.scheduling.SlotRepository;
import com.medicity.scheduling.SlotStatus;
import com.medicity.security.JwtService;
import com.medicity.support.AbstractIntegrationTest;
import com.medicity.user.Role;
import com.medicity.user.User;
import com.medicity.user.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The metrics endpoint is admin-only, and the business counters move with
 * what actually happened.
 *
 * <p>{@code @AutoConfigureObservability}: Spring Boot turns metric exporters
 * off in tests by default, so without it {@code /actuator/prometheus} does not
 * exist here even though it does in the running application.
 */
@AutoConfigureMockMvc
@AutoConfigureObservability
@DisplayName("Metrics")
class MetricsTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired MeterRegistry registry;
    @Autowired UserRepository userRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired PrescriptionRepository prescriptionRepository;
    @Autowired JwtService jwtService;
    @Autowired Clock clock;
    @Autowired BookingService bookingService;
    @Autowired TransactionTemplate transactions;

    private User admin;
    private User alice;
    private User bob;
    private Doctor doctor;

    @BeforeEach
    void setUp() {
        prescriptionRepository.deleteAllInBatch();
        appointmentRepository.deleteAll();
        slotRepository.deleteAll();
        patientRepository.deleteAll();
        doctorRepository.deleteAll();
        userRepository.deleteAll();

        admin = persistUser(Role.ADMIN);
        alice = persistUser(Role.PATIENT);
        persistPatient(alice);
        bob = persistUser(Role.PATIENT);
        persistPatient(bob);
        doctor = doctorRepository.save(Doctor.builder()
                .user(persistUser(Role.DOCTOR))
                .specialization("Cardiology")
                .licenseNumber("LIC-" + UUID.randomUUID().toString().substring(0, 8))
                .consultationFee(new BigDecimal("900.00"))
                .yearsExperience(10)
                .build());
    }

    @Test
    @DisplayName("only an admin can read metrics; health stays public")
    void metricsAreAdminOnly() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").header("Authorization", bearer(alice)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics").header("Authorization", bearer(alice)))
                .andExpect(status().isForbidden());

        mvc.perform(get("/actuator/prometheus").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("http_server_requests_seconds_bucket")))
                .andExpect(content().string(containsString("application=\"medicity-api\"")));

        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a booking and a lost race are counted under different outcomes")
    void bookingOutcomesAreCounted() throws Exception {
        double booked = bookings("booked");
        double lost = bookings("slot_already_booked");
        AppointmentSlot slot = persistSlot();

        book(alice, slot).andExpect(status().isCreated());
        book(bob, slot).andExpect(status().isConflict());

        assertThat(bookings("booked")).isEqualTo(booked + 1);
        assertThat(bookings("slot_already_booked")).isEqualTo(lost + 1);

        mvc.perform(get("/actuator/prometheus").header("Authorization", bearer(admin)))
                .andExpect(content().string(containsString("medicity_bookings_total")));
    }

    @Test
    @DisplayName("a booking whose transaction rolls back is not counted")
    void rolledBackBookingIsNotCounted() {
        double booked = bookings("booked");
        AppointmentSlot slot = persistSlot();
        UUID patientId = patientRepository.findByUserId(alice.getId()).orElseThrow().getId();

        // The insert succeeds, then the enclosing transaction is rolled back,
        // as it would be if anything later in the same request failed.
        transactions.executeWithoutResult(tx -> {
            bookingService.book(slot.getId(), patientId, "Check-up");
            tx.setRollbackOnly();
        });

        assertThat(appointmentRepository.count()).isZero();
        assertThat(bookings("booked")).isEqualTo(booked);
    }

    // --- helpers --------------------------------------------------------

    private double bookings(String outcome) {
        var counter = registry.find("medicity.bookings").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private ResultActions book(User user, AppointmentSlot slot) throws Exception {
        return mvc.perform(post("/api/v1/appointments")
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slotId\":\"" + slot.getId() + "\",\"reason\":\"Check-up\"}"));
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.issueAccessToken(user);
    }

    private AppointmentSlot persistSlot() {
        Instant start = clock.instant().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        return slotRepository.save(AppointmentSlot.builder()
                .doctor(doctor)
                .startsAt(start)
                .endsAt(start.plus(30, ChronoUnit.MINUTES))
                .status(SlotStatus.OPEN)
                .build());
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

    private void persistPatient(User user) {
        patientRepository.save(Patient.builder()
                .user(user)
                .dateOfBirth(LocalDate.of(1993, 3, 21))
                .gender(Patient.Gender.UNDISCLOSED)
                .build());
    }
}
