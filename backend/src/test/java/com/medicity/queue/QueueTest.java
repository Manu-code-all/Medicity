package com.medicity.queue;

import com.medicity.audit.AuditLog;
import com.medicity.clinical.PrescriptionRepository;
import com.medicity.common.ConflictException;
import com.medicity.common.ValidationException;
import com.medicity.doctor.Doctor;
import com.medicity.doctor.DoctorRepository;
import com.medicity.outbox.Outbox;
import com.medicity.patient.Patient;
import com.medicity.patient.PatientRepository;
import com.medicity.scheduling.AppointmentRepository;
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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
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

/**
 * Walk-in tokens. The service is built here with a clock fixed at 10 am India
 * time, so the "tokens between 7 am and 9 pm" rule does not depend on when CI
 * happens to run; each call goes through a transaction, as the bean's would.
 */
@AutoConfigureMockMvc
@DisplayName("Walk-in queue")
class QueueTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired NamedParameterJdbcTemplate named;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired DoctorRepository doctorRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired UserRepository userRepository;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired PrescriptionRepository prescriptionRepository;
    @Autowired AuditLog auditLog;
    @Autowired Outbox outbox;
    @Autowired JwtService jwtService;

    private QueueService queue;
    private Doctor menon;
    private User menonUser;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM queue_days");
        prescriptionRepository.deleteAllInBatch();
        appointmentRepository.deleteAll();
        slotRepository.deleteAll();
        patientRepository.deleteAll();
        doctorRepository.deleteAll();
        userRepository.deleteAll();

        menonUser = persistUser("Dr. Kavitha Menon", Role.DOCTOR);
        menon = doctorRepository.save(Doctor.builder()
                .user(menonUser)
                .specialization("General Medicine")
                .licenseNumber("LIC-" + UUID.randomUUID().toString().substring(0, 8))
                .consultationFee(new BigDecimal("600.00"))
                .yearsExperience(12)
                .build());
        queue = at(LocalTime.of(10, 0));
    }

    @Test
    @DisplayName("tokens are numbered from #101; each shows who is ahead and roughly how long")
    void numbersAndPlaces() {
        Patient meera = patient("Meera Nair");
        Patient arjun = patient("Arjun Mehta");

        QueueService.Token first = join(meera);
        QueueService.Token second = join(arjun);

        assertThat(first.tokenNo()).isEqualTo(101);
        assertThat(second.tokenNo()).isEqualTo(102);
        assertThat(second.ahead()).isEqualTo(1);
        // Fifteen minutes each when the doctor has no hours set: one ahead, then Arjun.
        assertThat(second.estimatedWaitMinutes()).isEqualTo(30);
        QueueService.Status status = queue.status(menon.getId());
        assertThat(status.open()).isTrue();
        assertThat(status.waiting()).isEqualTo(2);
        assertThat(status.estimatedWaitMinutes()).isEqualTo(30);
    }

    @Test
    @DisplayName("on the doctor's day off the queue says so and gives no tokens")
    void noTokensOnLeave() {
        jdbc.update("INSERT INTO doctor_leave (doctor_id, day) VALUES (?, ?)", menon.getId(),
                LocalDate.now(QueueService.CLINIC_ZONE));

        assertThat(queue.status(menon.getId()).closedReason()).isEqualTo("The doctor is not in today.");
        assertThatThrownBy(() -> join(patient("Meera Nair")))
                .isInstanceOf(com.medicity.common.ValidationException.class)
                .hasMessageContaining("not in today");
    }

    @Test
    @DisplayName("ten patients joining at the same moment get #101 to #110: no duplicates, no gaps")
    void concurrentJoinsAreNumberedInOrder() throws Exception {
        List<Patient> patients = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            patients.add(patient("Patient " + i));
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> numbers = new ArrayList<>();
        for (Patient p : patients) {
            numbers.add(pool.submit(() -> {
                start.await();
                return join(p).tokenNo();
            }));
        }
        start.countDown();
        List<Integer> got = new ArrayList<>();
        for (Future<Integer> f : numbers) {
            got.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(got).containsExactlyInAnyOrder(101, 102, 103, 104, 105, 106, 107, 108, 109, 110);
    }

    @Test
    @DisplayName("one place per patient: a second join is refused and does not use up a number")
    void onePlacePerPatient() {
        Patient meera = patient("Meera Nair");
        join(meera);

        assertThatThrownBy(() -> join(meera))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "ALREADY_IN_QUEUE");
        assertThat(join(patient("Arjun Mehta")).tokenNo()).isEqualTo(102);
    }

    @Test
    @DisplayName("the desk calls the lowest waiting token, the patient is told, and places move up")
    void callNextInOrder() {
        Patient meera = patient("Meera Nair");
        Patient arjun = patient("Arjun Mehta");
        QueueService.Token first = join(meera);
        join(arjun);

        QueueService.Token called = transactions.execute(s -> queue.callNext(menon.getId()));
        assertThat(called.tokenNo()).isEqualTo(101);
        assertThat(called.status()).isEqualTo("CALLED");
        assertThat(queue.status(menon.getId()).nowServing()).isEqualTo(101);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE event_type = 'QUEUE_CALLED' AND aggregate_id = ?",
                Integer.class, first.id())).isEqualTo(1);
        // Arjun is next now.
        assertThat(queue.desk(menon.getId())).filteredOn(t -> t.tokenNo() == 102)
                .singleElement().extracting(QueueService.Token::ahead).isEqualTo(0);

        transactions.execute(s -> queue.finish(menon.getId(), first.id(), true));
        transactions.execute(s -> queue.callNext(menon.getId()));
        assertThatThrownBy(() -> transactions.execute(s -> queue.callNext(menon.getId())))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("code", "QUEUE_EMPTY");
    }

    @Test
    @DisplayName("a closed queue takes no new tokens, and none are given outside 7 am to 9 pm")
    void closedAndOutsideHours() {
        Patient meera = patient("Meera Nair");
        QueueService.Token waiting = join(meera);
        transactions.execute(s -> queue.setOpen(menon.getId(), false));

        assertThatThrownBy(() -> join(patient("Arjun Mehta")))
                .hasFieldOrPropertyWithValue("code", "QUEUE_CLOSED");
        assertThat(queue.status(menon.getId()).open()).isFalse();
        // Meera keeps her place.
        assertThat(queue.desk(menon.getId())).extracting(QueueService.Token::id).containsExactly(waiting.id());

        QueueService late = at(LocalTime.of(21, 30));
        assertThatThrownBy(() -> transactions.execute(s -> late.join(menon.getId(), patient("Kavya Reddy"), null)))
                .hasFieldOrPropertyWithValue("code", "QUEUE_CLOSED");
    }

    @Test
    @DisplayName("the account holder sees family members' tokens and can give a place up; nobody else can")
    void familyAndLeaving() throws Exception {
        User meeraUser = persistUser("Meera Nair", Role.PATIENT);
        Patient lalitha = patientRepository.save(Patient.builder()
                .guardianUserId(meeraUser.getId())
                .fullName("Lalitha Nair")
                .relationship(Patient.Relationship.PARENT)
                .dateOfBirth(LocalDate.of(1961, 4, 10))
                .gender(Patient.Gender.FEMALE)
                .build());
        QueueService.Token hers = join(lalitha);
        User stranger = persistUser("Arjun Mehta", Role.PATIENT);
        patientRepository.save(Patient.builder().user(stranger)
                .dateOfBirth(LocalDate.of(1988, 2, 3)).gender(Patient.Gender.MALE).build());

        assertThat(queue.mine(meeraUser.getId())).extracting(QueueService.Token::patientName)
                .containsExactly("Lalitha Nair");

        mvc.perform(post("/api/v1/queue/tokens/" + hers.id() + "/leave").header("Authorization", bearer(stranger)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/queue/tokens/" + hers.id() + "/leave").header("Authorization", bearer(meeraUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LEFT"));
    }

    @Test
    @DisplayName("the queue's status is public; the front desk is the doctor's alone")
    void access() throws Exception {
        mvc.perform(get("/api/v1/doctors/" + menon.getId() + "/queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waiting").value(0));
        User patientUser = persistUser("Meera Nair", Role.PATIENT);
        mvc.perform(get("/api/v1/doctors/me/queue").header("Authorization", bearer(patientUser)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/doctors/me/queue").header("Authorization", bearer(menonUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokens").isArray());
    }

    // --- helpers --------------------------------------------------------------

    private QueueService.Token join(Patient p) {
        return transactions.execute(s -> queue.join(menon.getId(), p, "Fever"));
    }

    private QueueService at(LocalTime time) {
        ZonedDateTime when = ZonedDateTime.now(QueueService.CLINIC_ZONE).with(time);
        return new QueueService(named, doctorRepository, auditLog, outbox,
                Clock.fixed(when.toInstant(), QueueService.CLINIC_ZONE));
    }

    private Patient patient(String name) {
        return patientRepository.save(Patient.builder().user(persistUser(name, Role.PATIENT))
                .dateOfBirth(LocalDate.of(1990, 1, 1)).gender(Patient.Gender.UNDISCLOSED).build());
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

    private String bearer(User user) {
        return "Bearer " + jwtService.issueAccessToken(user);
    }
}
