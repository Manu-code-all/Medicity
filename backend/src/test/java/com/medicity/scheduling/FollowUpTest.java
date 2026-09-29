package com.medicity.scheduling;

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
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Free follow-up questions: three, within seven days, between this visit's patient and doctor only. */
@AutoConfigureMockMvc
@DisplayName("Follow-up questions")
class FollowUpTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired PrescriptionRepository prescriptionRepository;
    @Autowired JwtService jwtService;
    @Autowired Clock clock;

    private User meeraUser;
    private Patient meera;
    private User raoUser;
    private Doctor rao;

    @BeforeEach
    void setUp() {
        prescriptionRepository.deleteAllInBatch();
        appointmentRepository.deleteAll();
        slotRepository.deleteAll();
        patientRepository.deleteAll();
        doctorRepository.deleteAll();
        userRepository.deleteAll();
        meeraUser = persistUser(Role.PATIENT);
        meera = patientRepository.save(Patient.builder().user(meeraUser)
                .dateOfBirth(LocalDate.of(1993, 3, 21)).gender(Patient.Gender.FEMALE).build());
        raoUser = persistUser(Role.DOCTOR);
        rao = doctorRepository.save(Doctor.builder().user(raoUser).specialization("Cardiology")
                .licenseNumber("LIC-" + UUID.randomUUID().toString().substring(0, 8))
                .consultationFee(new BigDecimal("900.00")).yearsExperience(10).build());
    }

    @Test
    @DisplayName("three questions, answered in between; the fourth is refused; each side is notified")
    void threeQuestions() throws Exception {
        Appointment visit = visit(-2, AppointmentStatus.COMPLETED);
        for (int i = 1; i <= 3; i++) {
            ask(meeraUser, visit, "Question " + i).andExpect(status().isOk())
                    .andExpect(jsonPath("$.questionsLeft").value(3 - i))
                    .andExpect(jsonPath("$.awaitingDoctor").value(true));
            ask(raoUser, visit, "Answer " + i).andExpect(status().isOk())
                    .andExpect(jsonPath("$.awaitingDoctor").value(false));
        }
        ask(meeraUser, visit, "One more?").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FOLLOWUP_LIMIT"));

        mvc.perform(get(url(visit)).header("Authorization", bearer(meeraUser)))
                .andExpect(jsonPath("$.messages", hasSize(6)))
                .andExpect(jsonPath("$.messages[0].sender").value("PATIENT"))
                .andExpect(jsonPath("$.messages[1].body").value("Answer 1"));
        assertThat(events("FOLLOWUP_ASKED", visit)).isEqualTo(3);
        assertThat(events("FOLLOWUP_ANSWERED", visit)).isEqualTo(3);
    }

    @Test
    @DisplayName("five questions sent at the same moment: exactly three are taken")
    void limitHoldsUnderConcurrency() throws Exception {
        Appointment visit = visit(-1, AppointmentStatus.COMPLETED);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int n = i;
            results.add(pool.submit(() -> {
                start.await();
                return ask(meeraUser, visit, "Question " + n).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(statuses).filteredOn(s -> s == 200).hasSize(3);
        assertThat(statuses).filteredOn(s -> s == 422).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit_followups WHERE appointment_id = ?",
                Integer.class, visit.getId())).isEqualTo(3);
    }

    @Test
    @DisplayName("not after seven days, not before the visit happened, not by anyone else; nothing to answer is refused")
    void boundaries() throws Exception {
        ask(meeraUser, visit(-8, AppointmentStatus.COMPLETED), "Late question")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FOLLOWUP_CLOSED"));
        ask(meeraUser, visit(2, AppointmentStatus.BOOKED), "Early question")
                .andExpect(jsonPath("$.code").value("FOLLOWUP_NOT_OPEN"));

        Appointment recent = visit(-1, AppointmentStatus.COMPLETED);
        User stranger = persistUser(Role.PATIENT);
        patientRepository.save(Patient.builder().user(stranger)
                .dateOfBirth(LocalDate.of(1990, 1, 1)).gender(Patient.Gender.MALE).build());
        ask(stranger, recent, "Hello").andExpect(status().isForbidden());
        mvc.perform(get(url(recent)).header("Authorization", bearer(stranger))).andExpect(status().isForbidden());
        ask(raoUser, recent, "Unprompted").andExpect(jsonPath("$.code").value("NOTHING_TO_ANSWER"));
    }

    private ResultActions ask(User user, Appointment visit, String body) throws Exception {
        return mvc.perform(post(url(visit)).header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"" + body + "\"}"));
    }

    private static String url(Appointment visit) {
        return "/api/v1/appointments/" + visit.getId() + "/followups";
    }

    private int events(String type, Appointment visit) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = ? AND aggregate_id IN "
                + "(SELECT id FROM visit_followups WHERE appointment_id = ?)", Integer.class, type, visit.getId());
    }

    /** A visit that started this many days from now (negative: in the past). */
    private Appointment visit(int days, AppointmentStatus status) {
        Instant start = clock.instant().plus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        AppointmentSlot slot = slotRepository.save(AppointmentSlot.builder().doctor(rao).startsAt(start)
                .endsAt(start.plus(30, ChronoUnit.MINUTES)).build());
        return appointmentRepository.save(Appointment.builder().slot(slot).patient(meera).status(status)
                .scheduledAt(start).build());
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.issueAccessToken(user);
    }

    private User persistUser(Role role) {
        User user = User.builder().passwordHash("{noop}x").fullName("Test " + role.name().toLowerCase())
                .role(role).enabled(true).build();
        user.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@medicity.test");
        return userRepository.save(user);
    }
}
