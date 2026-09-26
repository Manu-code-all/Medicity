package com.medicity.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicity.clinical.PrescriptionRepository;
import com.medicity.doctor.Doctor;
import com.medicity.doctor.DoctorRepository;
import com.medicity.patient.Patient;
import com.medicity.patient.PatientRepository;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A booking retried with the same {@code Idempotency-Key} returns the original
 * booking instead of creating a second one or failing.
 */
@AutoConfigureMockMvc
@DisplayName("Idempotent booking")
class IdempotentBookingTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository userRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired PrescriptionRepository prescriptionRepository;
    @Autowired BookingService bookingService;
    @Autowired JwtService jwtService;
    @Autowired Clock clock;

    private User aliceUser;
    private User bobUser;
    private Patient bob;
    private Doctor doctor;

    @BeforeEach
    void setUp() {
        prescriptionRepository.deleteAllInBatch();
        appointmentRepository.deleteAll();
        slotRepository.deleteAll();
        patientRepository.deleteAll();
        doctorRepository.deleteAll();
        userRepository.deleteAll();

        aliceUser = persistUser(Role.PATIENT);
        persistPatient(aliceUser);
        bobUser = persistUser(Role.PATIENT);
        bob = persistPatient(bobUser);
        doctor = doctorRepository.save(Doctor.builder()
                .user(persistUser(Role.DOCTOR))
                .specialization("Cardiology")
                .licenseNumber("LIC-" + UUID.randomUUID().toString().substring(0, 8))
                .consultationFee(new BigDecimal("900.00"))
                .yearsExperience(10)
                .build());
    }

    @Test
    @DisplayName("a retry with the same key returns the original booking, marked as a replay")
    void retryReturnsTheOriginal() throws Exception {
        AppointmentSlot slot = persistSlot(2);
        String key = UUID.randomUUID().toString();

        String first = book(aliceUser, slot, key)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andReturn().getResponse().getContentAsString();

        String second = book(aliceUser, slot, key)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(second).path("id")).isEqualTo(json.readTree(first).path("id"));
        assertThat(appointmentRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("without a key, the same retry is refused as a clash (the problem keys solve)")
    void withoutKeyRetryIsAConflict() throws Exception {
        AppointmentSlot slot = persistSlot(2);

        book(aliceUser, slot, null).andExpect(status().isCreated());
        book(aliceUser, slot, null).andExpect(status().isConflict());
        assertThat(appointmentRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a key reused for a different request is refused, not answered with the wrong booking")
    void keyReusedForDifferentRequest() throws Exception {
        String key = UUID.randomUUID().toString();
        book(aliceUser, persistSlot(2), key).andExpect(status().isCreated());

        book(aliceUser, persistSlot(3), key)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(appointmentRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("keys belong to one user: another patient with the same key gets their own booking")
    void keysAreScopedToTheUser() throws Exception {
        String key = "shared-key";
        String alices = book(aliceUser, persistSlot(2), key).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String bobs = book(bobUser, persistSlot(3), key)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(bobs).path("id")).isNotEqualTo(json.readTree(alices).path("id"));
        assertThat(json.readTree(bobs).path("patientId").asText()).isEqualTo(bob.getId().toString());
    }

    @Test
    @DisplayName("a failed request is not remembered: the retry runs again")
    void failuresAreNotStored() throws Exception {
        AppointmentSlot slot = persistSlot(2);
        Appointment bobs = bookingService.book(slot.getId(), bob.getId(), "Taken first");
        String key = UUID.randomUUID().toString();

        book(aliceUser, slot, key).andExpect(status().isConflict());

        // The slot frees up; the same retry now succeeds instead of replaying the 409.
        bookingService.cancel(bobs.getId(), "Changed plans");
        book(aliceUser, slot, key)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"));
    }

    @Test
    @DisplayName("8 simultaneous requests with one key create one booking, and every one gets it")
    void simultaneousDuplicatesShareOneBooking() throws Exception {
        AppointmentSlot slot = persistSlot(2);
        String key = UUID.randomUUID().toString();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MockHttpServletResponse>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return book(aliceUser, slot, key).andReturn().getResponse();
            }));
        }
        start.countDown();

        List<String> ids = new ArrayList<>();
        int replays = 0;
        for (Future<MockHttpServletResponse> result : results) {
            MockHttpServletResponse response = result.get(30, TimeUnit.SECONDS);
            assertThat(response.getStatus()).isEqualTo(201);
            ids.add(json.readTree(response.getContentAsString()).path("id").asText());
            if ("true".equals(response.getHeader("Idempotent-Replayed"))) replays++;
        }
        pool.shutdown();

        assertThat(ids).containsOnly(ids.get(0));
        assertThat(replays).isEqualTo(threads - 1);
        assertThat(appointmentRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a malformed key is a 400")
    void malformedKey() throws Exception {
        book(aliceUser, persistSlot(2), "has spaces in it")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    // --- fixtures -------------------------------------------------------

    private ResultActions book(User user, AppointmentSlot slot, String key) throws Exception {
        var request = post("/api/v1/appointments")
                .header("Authorization", "Bearer " + jwtService.issueAccessToken(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slotId\":\"" + slot.getId() + "\",\"reason\":\"Check-up\"}");
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
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

    private Patient persistPatient(User user) {
        return patientRepository.save(Patient.builder()
                .user(user)
                .dateOfBirth(LocalDate.of(1993, 3, 21))
                .gender(Patient.Gender.UNDISCLOSED)
                .build());
    }
}
