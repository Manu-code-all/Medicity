package com.medicity.doctor;

import com.medicity.clinical.PrescribingService;
import com.medicity.clinical.PrescribingService.PrescriptionDraft;
import com.medicity.clinical.PrescriptionRepository;
import com.medicity.clinical.VisitService;
import com.medicity.common.ConflictException;
import com.medicity.patient.Patient;
import com.medicity.patient.PatientRepository;
import com.medicity.pharmacy.Medicine;
import com.medicity.pharmacy.MedicineRepository;
import com.medicity.scheduling.*;
import com.medicity.security.JwtService;
import com.medicity.support.AbstractIntegrationTest;
import com.medicity.user.Role;
import com.medicity.user.User;
import com.medicity.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The doctor's workspace, through the real HTTP stack: who can reach it, the
 * rules for closing a visit and prescribing, and the races the database decides.
 */
@AutoConfigureMockMvc
@DisplayName("Doctor workspace")
class DoctorWorkspaceTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired PrescriptionRepository prescriptionRepository;
    @Autowired MedicineRepository medicineRepository;
    @Autowired BookingService bookingService;
    @Autowired VisitService visitService;
    @Autowired PrescribingService prescribingService;
    @Autowired JwtService jwtService;
    @Autowired Clock clock;

    private User raoUser;
    private User iyerUser;
    private User patientUser;
    private Doctor rao;
    private Doctor iyer;
    private Patient patient;
    private Patient stranger;
    private Medicine paracetamol;
    private Medicine omeprazole;
    private Instant now;

    @BeforeEach
    void setUp() {
        cleanUp();
        now = clock.instant().truncatedTo(ChronoUnit.MINUTES);

        raoUser = persistUser("Dr. Anjali Rao", Role.DOCTOR);
        rao = persistDoctor(raoUser, "Cardiology");
        iyerUser = persistUser("Dr. Suresh Iyer", Role.DOCTOR);
        iyer = persistDoctor(iyerUser, "Neurology");

        patientUser = persistUser("Arjun Mehta", Role.PATIENT);
        patient = patientRepository.save(Patient.builder().user(patientUser)
                .dateOfBirth(LocalDate.of(1988, 2, 3)).gender(Patient.Gender.MALE).bloodGroup("A+").build());
        stranger = patientRepository.save(Patient.builder().user(persistUser("Kavya Reddy", Role.PATIENT))
                .dateOfBirth(LocalDate.of(2001, 11, 19)).gender(Patient.Gender.FEMALE).build());

        paracetamol = persistMedicine("Paracetamol");
        omeprazole = persistMedicine("Omeprazole");
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    // --- access ---------------------------------------------------------

    @Test
    @DisplayName("the workspace is not public, even though other GETs under /doctors are")
    void workspaceIsNotPublic() throws Exception {
        mvc.perform(get("/api/v1/doctors/me/visits").param("from", now.toString())
                        .param("to", now.plus(1, ChronoUnit.DAYS).toString()))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/doctors/me/visits").param("from", now.toString())
                        .param("to", now.plus(1, ChronoUnit.DAYS).toString())
                        .header("Authorization", bearer(patientUser)))
                .andExpect(status().isForbidden());

        // The public directory is still public.
        mvc.perform(get("/api/v1/doctors")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the schedule lists only my visits inside the window, with the patient's details")
    void scheduleShowsOwnVisitsInWindow() throws Exception {
        visit(rao, patient, now.minus(1, ChronoUnit.HOURS));
        visit(rao, stranger, now.plus(3, ChronoUnit.HOURS));
        visit(rao, patient, now.plus(3, ChronoUnit.DAYS));          // outside the window
        visit(iyer, patient, now.plus(1, ChronoUnit.HOURS));        // another doctor's

        mvc.perform(get("/api/v1/doctors/me/visits")
                        .param("from", now.minus(12, ChronoUnit.HOURS).toString())
                        .param("to", now.plus(12, ChronoUnit.HOURS).toString())
                        .header("Authorization", bearer(raoUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].patient.fullName").value("Arjun Mehta"))
                .andExpect(jsonPath("$[0].patient.bloodGroup").value("A+"))
                .andExpect(jsonPath("$[1].patient.fullName").value("Kavya Reddy"));
    }

    // --- closing a visit ------------------------------------------------

    @Test
    @DisplayName("a visit can be completed only once it has started, and only once")
    void completeRules() throws Exception {
        Appointment future = visit(rao, patient, now.plus(2, ChronoUnit.HOURS));
        Appointment started = visit(rao, stranger, now.minus(20, ChronoUnit.MINUTES));

        mvc.perform(post(visitUrl(future) + "/complete").header("Authorization", bearer(raoUser)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VISIT_NOT_STARTED"));

        mvc.perform(post(visitUrl(started) + "/complete").header("Authorization", bearer(raoUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mvc.perform(post(visitUrl(started) + "/no-show").header("Authorization", bearer(raoUser)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_VISIT_STATE"));

        assertThat(auditCount("VISIT_COMPLETED", started.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("another doctor cannot close my visit, and the attempt is audited")
    void otherDoctorIsRefused() throws Exception {
        Appointment started = visit(rao, patient, now.minus(20, ChronoUnit.MINUTES));

        mvc.perform(post(visitUrl(started) + "/complete").header("Authorization", bearer(iyerUser)))
                .andExpect(status().isForbidden());

        assertThat(auditCount("ACCESS_DENIED", started.getId())).isEqualTo(1);
        assertThat(appointmentRepository.findById(started.getId()).orElseThrow().getStatus())
                .isEqualTo(AppointmentStatus.BOOKED);
    }

    @Test
    @DisplayName("patient cancels while the doctor completes: exactly one wins")
    void cancelVersusCompleteRace() throws Exception {
        // Repeated because a single run could pass by luck of scheduling.
        for (int round = 0; round < 10; round++) {
            Appointment started = visit(rao, patient, now.minus(30 + round, ChronoUnit.MINUTES).minus(round, ChronoUnit.DAYS));

            List<Callable<Boolean>> contenders = List.of(
                    () -> { bookingService.cancel(started.getId(), "Feeling better"); return true; },
                    () -> { visitService.complete(started.getId(), rao.getId()); return true; });
            int wins = race(contenders);

            assertThat(wins).as("round %d", round).isEqualTo(1);
            AppointmentStatus finalStatus = appointmentRepository.findById(started.getId()).orElseThrow().getStatus();
            assertThat(finalStatus).isIn(AppointmentStatus.CANCELLED, AppointmentStatus.COMPLETED);
        }
    }

    // --- prescribing ----------------------------------------------------

    @Test
    @DisplayName("prescribing requires a completed visit; a second original is refused")
    void prescribeRules() throws Exception {
        Appointment started = visit(rao, patient, now.minus(20, ChronoUnit.MINUTES));

        mvc.perform(post(visitUrl(started) + "/prescriptions").header("Authorization", bearer(raoUser))
                        .contentType(MediaType.APPLICATION_JSON).content(rxJson("Angina", paracetamol, 10)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VISIT_NOT_COMPLETED"));

        visitService.complete(started.getId(), rao.getId());

        mvc.perform(post(visitUrl(started) + "/prescriptions").header("Authorization", bearer(raoUser))
                        .contentType(MediaType.APPLICATION_JSON).content(rxJson("Stable angina", paracetamol, 10)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.prescription.diagnosis").value("Stable angina"))
                .andExpect(jsonPath("$.prescription.items[0].quantity").value(10));

        mvc.perform(post(visitUrl(started) + "/prescriptions").header("Authorization", bearer(raoUser))
                        .contentType(MediaType.APPLICATION_JSON).content(rxJson("Again", paracetamol, 5)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRESCRIPTION_EXISTS"));
    }

    @Test
    @DisplayName("invalid prescriptions are rejected before they reach the database")
    void invalidPrescriptions() throws Exception {
        Appointment started = visit(rao, patient, now.minus(20, ChronoUnit.MINUTES));
        visitService.complete(started.getId(), rao.getId());

        mvc.perform(post(visitUrl(started) + "/prescriptions").header("Authorization", bearer(raoUser))
                        .contentType(MediaType.APPLICATION_JSON).content(rxJson("Angina", paracetamol, 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        String duplicate = """
                {"diagnosis":"Angina","items":[
                  {"medicineId":"%s","dosage":"500mg","frequency":"Twice daily","durationDays":5,"quantity":10},
                  {"medicineId":"%s","dosage":"500mg","frequency":"Once daily","durationDays":5,"quantity":5}]}
                """.formatted(paracetamol.getId(), paracetamol.getId());
        mvc.perform(post(visitUrl(started) + "/prescriptions").header("Authorization", bearer(raoUser))
                        .contentType(MediaType.APPLICATION_JSON).content(duplicate))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUPLICATE_MEDICINE"));
    }

    @Test
    @DisplayName("8 tabs issuing the first prescription at once: exactly one is created")
    void concurrentIssueCreatesOne() throws Exception {
        Appointment started = visit(rao, patient, now.minus(20, ChronoUnit.MINUTES));
        visitService.complete(started.getId(), rao.getId());

        List<Callable<Boolean>> tabs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tabs.add(() -> {
                prescribingService.issue(started.getId(), rao.getId(), draft("Angina", paracetamol, 10));
                return true;
            });
        }

        assertThat(race(tabs)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM prescriptions WHERE appointment_id = ?",
                Integer.class, started.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("a correction replaces the original for the patient; the original cannot be corrected twice")
    void correctionReplacesOriginal() throws Exception {
        Appointment started = visit(rao, patient, now.minus(20, ChronoUnit.MINUTES));
        visitService.complete(started.getId(), rao.getId());
        UUID original = prescribingService.issue(started.getId(), rao.getId(), draft("Angina", paracetamol, 15)).getId();

        mvc.perform(post("/api/v1/doctors/me/prescriptions/" + original + "/corrections")
                        .header("Authorization", bearer(iyerUser))
                        .contentType(MediaType.APPLICATION_JSON).content(rxJson("Angina", omeprazole, 14)))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/doctors/me/prescriptions/" + original + "/corrections")
                        .header("Authorization", bearer(raoUser))
                        .contentType(MediaType.APPLICATION_JSON).content(rxJson("Stable angina", omeprazole, 14)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.prescription.revised").value(true));

        mvc.perform(post("/api/v1/doctors/me/prescriptions/" + original + "/corrections")
                        .header("Authorization", bearer(raoUser))
                        .contentType(MediaType.APPLICATION_JSON).content(rxJson("Again", paracetamol, 5)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CORRECTED"));

        mvc.perform(get("/api/v1/patients/me/prescriptions").header("Authorization", bearer(patientUser)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].diagnosis").value("Stable angina"))
                .andExpect(jsonPath("$[0].items[0].medicine").value(omeprazole.getName()));
    }

    // --- patient history ------------------------------------------------

    @Test
    @DisplayName("a doctor can read the history of their own patients only, and each read is audited")
    void patientHistoryIsScoped() throws Exception {
        Appointment seenByIyer = visit(iyer, patient, now.minus(40, ChronoUnit.DAYS));
        visitService.complete(seenByIyer.getId(), iyer.getId());
        visit(rao, patient, now.plus(1, ChronoUnit.DAYS));

        mvc.perform(get("/api/v1/doctors/me/patients/" + patient.getId() + "/history")
                        .header("Authorization", bearer(raoUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patient.fullName").value("Arjun Mehta"))
                // Includes the visit with the other doctor: the treating doctor
                // needs the whole picture, not just their own slice.
                .andExpect(jsonPath("$.visits", hasSize(2)));

        mvc.perform(get("/api/v1/doctors/me/patients/" + stranger.getId() + "/history")
                        .header("Authorization", bearer(raoUser)))
                .andExpect(status().isForbidden());

        assertThat(auditCount("PATIENT_HISTORY_VIEWED", patient.getId())).isEqualTo(1);
        assertThat(auditCount("ACCESS_DENIED", stranger.getId())).isEqualTo(1);
    }

    // --- helpers --------------------------------------------------------

    /** Releases every contender at the same instant; returns how many succeeded. */
    private static int race(List<Callable<Boolean>> contenders) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(contenders.size());
        CountDownLatch ready = new CountDownLatch(contenders.size());
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (Callable<Boolean> contender : contenders) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    if (contender.call()) {
                        wins.incrementAndGet();
                    }
                } catch (ConflictException
                         | com.medicity.common.ValidationException
                         | org.springframework.orm.ObjectOptimisticLockingFailureException expected) {
                    // A losing contender: refused by a constraint, a state check,
                    // or the version check.
                }
                return null;
            }));
        }
        ready.await();
        go.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        return wins.get();
    }

    private String visitUrl(Appointment a) {
        return "/api/v1/doctors/me/visits/" + a.getId();
    }

    private int auditCount(String action, UUID entityId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ? AND entity_id = ?",
                Integer.class, action, entityId.toString());
    }

    private static PrescriptionDraft draft(String diagnosis, Medicine medicine, int quantity) {
        return new PrescriptionDraft(diagnosis, null, List.of(
                new PrescriptionDraft.Item(medicine.getId(), "500mg", "Twice daily", 5, quantity)));
    }

    private static String rxJson(String diagnosis, Medicine medicine, int quantity) {
        return """
                {"diagnosis":"%s","notes":"Review in 2 weeks","items":[
                  {"medicineId":"%s","dosage":"500mg","frequency":"Twice daily","durationDays":5,"quantity":%d}]}
                """.formatted(diagnosis, medicine.getId(), quantity);
    }

    private Appointment visit(Doctor doctor, Patient p, Instant start) {
        AppointmentSlot slot = slotRepository.save(AppointmentSlot.builder()
                .doctor(doctor).startsAt(start).endsAt(start.plus(30, ChronoUnit.MINUTES)).build());
        return appointmentRepository.save(Appointment.builder()
                .slot(slot).patient(p).status(AppointmentStatus.BOOKED).reason("Check-up").scheduledAt(start).build());
    }

    private void cleanUp() {
        jdbc.update("DELETE FROM prescription_dispensations");
        prescriptionRepository.deleteAllInBatch();
        appointmentRepository.deleteAll();
        slotRepository.deleteAll();
        patientRepository.deleteAll();
        doctorRepository.deleteAll();
        userRepository.deleteAll();
        jdbc.update("DELETE FROM medicines WHERE generic_name LIKE 'dw-test-%'");
    }

    private Medicine persistMedicine(String name) {
        return medicineRepository.save(Medicine.builder()
                .name(name + "-" + UUID.randomUUID().toString().substring(0, 6))
                .genericName("dw-test-" + name).form(Medicine.Form.TABLET).strength("500mg")
                .unitPrice(new BigDecimal("40.00")).build());
    }

    private Doctor persistDoctor(User user, String specialization) {
        return doctorRepository.save(Doctor.builder().user(user).specialization(specialization)
                .licenseNumber("LIC-" + UUID.randomUUID().toString().substring(0, 8))
                .consultationFee(new BigDecimal("1000.00")).yearsExperience(10).build());
    }

    private User persistUser(String name, Role role) {
        User user = User.builder().passwordHash("{noop}irrelevant").fullName(name).role(role).enabled(true).build();
        user.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@medicity.test");
        return userRepository.save(user);
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.issueAccessToken(user);
    }
}
