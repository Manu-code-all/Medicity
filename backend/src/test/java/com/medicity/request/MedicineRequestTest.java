package com.medicity.request;

import com.jayway.jsonpath.JsonPath;
import com.medicity.clinical.PrescribingService;
import com.medicity.clinical.PrescribingService.PrescriptionDraft;
import com.medicity.clinical.PrescriptionRepository;
import com.medicity.clinical.VisitService;
import com.medicity.doctor.Doctor;
import com.medicity.doctor.DoctorRepository;
import com.medicity.outbox.OutboxRelay;
import com.medicity.patient.Patient;
import com.medicity.patient.PatientRepository;
import com.medicity.pharmacy.Medicine;
import com.medicity.pharmacy.MedicineRepository;
import com.medicity.scheduling.*;
import com.medicity.security.JwtService;
import com.medicity.store.Store;
import com.medicity.store.StoreRepository;
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
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
 * "Ask all nearby chemists": who receives a question, what they see, the
 * rules for answering, and how answers are compared.
 */
@AutoConfigureMockMvc
@DisplayName("Asking nearby chemists")
class MedicineRequestTest extends AbstractIntegrationTest {

    private static final double LAT = 12.9719;
    private static final double LNG = 77.6412;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired PrescriptionRepository prescriptionRepository;
    @Autowired MedicineRepository medicineRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired VisitService visitService;
    @Autowired PrescribingService prescribingService;
    @Autowired MedicineRequestService service;
    @Autowired OutboxRelay relay;
    @Autowired JwtService jwtService;
    @Autowired Clock clock;

    private User meeraUser;
    private User otherUser;
    private User doctorUser;
    private Doctor doctor;
    private Patient meera;
    private Medicine omeprazole;
    private Medicine omez;
    private Medicine cetirizine;
    private Medicine unrelated;
    private UUID prescriptionId;

    private User nearOwner;
    private User nearerOwner;
    private User quietOwner;
    private User farOwner;
    private User unverifiedOwner;
    private Store near;
    private Store nearer;

    @BeforeEach
    void setUp() {
        cleanUp();
        meeraUser = persistUser(Role.PATIENT, "Meera Nair");
        meera = persistPatient(meeraUser);
        otherUser = persistUser(Role.PATIENT, "Mallory Das");
        persistPatient(otherUser);
        doctorUser = persistUser(Role.DOCTOR, "Dr. Anjali Rao");
        doctor = doctorRepository.save(Doctor.builder().user(doctorUser).specialization("Cardiology")
                .licenseNumber("KA-RQ-" + UUID.randomUUID().toString().substring(0, 8))
                .consultationFee(new BigDecimal("900.00")).yearsExperience(10).build());

        omeprazole = persistMedicine("Omeprazole", "rq-test-omeprazole", "20mg", Medicine.Form.CAPSULE);
        omez = persistMedicine("Omez", "rq-test-omeprazole", "20mg", Medicine.Form.CAPSULE);
        cetirizine = persistMedicine("Cetirizine", "rq-test-cetirizine", "10mg", Medicine.Form.TABLET);
        unrelated = persistMedicine("Dolo", "rq-test-paracetamol", "650mg", Medicine.Form.TABLET);

        prescriptionId = prescribe(meera, List.of(
                new PrescriptionDraft.Item(omeprazole.getId(), "20mg", "Once daily before breakfast", 14, 14, true),
                new PrescriptionDraft.Item(cetirizine.getId(), "10mg", "At night", 5, 5, false)));

        nearOwner = persistUser(Role.CHEMIST, "Ravi Kumar");
        near = persistStore(nearOwner, "Sri Sai Medicals", LAT + 0.003, LNG, true);          // ~330 m
        nearerOwner = persistUser(Role.CHEMIST, "Fatima Sheikh");
        nearer = persistStore(nearerOwner, "Green Cross", LAT + 0.001, LNG, true);           // ~110 m
        quietOwner = persistUser(Role.CHEMIST, "Anita Desai");
        persistStore(quietOwner, "CityCare", LAT + 0.015, LNG, true);                        // ~1.7 km
        farOwner = persistUser(Role.CHEMIST, "Joseph Thomas");
        persistStore(farOwner, "Far Away", LAT + 0.05, LNG, true);                           // ~5.6 km
        unverifiedOwner = persistUser(Role.CHEMIST, "New Owner");
        persistStore(unverifiedOwner, "Not Checked Yet", LAT + 0.001, LNG, false);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    // --- who receives it --------------------------------------------------

    @Test
    @DisplayName("a question goes to every verified store in the radius, and to no one else")
    void questionReachesVerifiedStoresInReach() throws Exception {
        UUID id = ask(3000);

        assertThat(recipients(id)).containsExactlyInAnyOrder("Sri Sai Medicals", "Green Cross", "CityCare");
        queue(nearOwner).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].patientName").value("Meera N."))
                .andExpect(jsonPath("$[0].doctorName").value("Dr. Anjali Rao"))
                .andExpect(jsonPath("$[0].medicines").value(2));
        queue(farOwner).andExpect(jsonPath("$", hasSize(0)));
        queue(unverifiedOwner).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("a store that was not asked cannot read the question, and the attempt is audited")
    void onlyRecipientsCanRead() throws Exception {
        UUID id = ask(3000);

        mvc.perform(get("/api/v1/stores/me/requests/" + id).header("Authorization", bearer(farOwner)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/stores/me/requests/" + id + "/answer").header("Authorization", bearer(farOwner))
                        .contentType(MediaType.APPLICATION_JSON).content(allYes(5.0, 2.0)))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_log WHERE action = 'ACCESS_DENIED' AND entity_id = ?
                """, Integer.class, id.toString())).isEqualTo(2);

        // Patients cannot reach the store side at all.
        mvc.perform(get("/api/v1/stores/me/requests/" + id).header("Authorization", bearer(meeraUser)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a store sees who prescribed it and what, but not the diagnosis or the patient's full name")
    void storeSeesTheVerifiedPrescription() throws Exception {
        UUID id = ask(3000);

        mvc.perform(get("/api/v1/stores/me/requests/" + id).header("Authorization", bearer(nearOwner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patientName").value("Meera N."))
                .andExpect(jsonPath("$.prescription.doctorName").value("Dr. Anjali Rao"))
                .andExpect(jsonPath("$.prescription.doctorRegistration").value(doctor.getLicenseNumber()))
                .andExpect(jsonPath("$.prescription.diagnosis").doesNotExist())
                .andExpect(jsonPath("$.diagnosis").doesNotExist())
                .andExpect(jsonPath("$.items[?(@.name == '%s')].frequency".formatted(omeprazole.getName()))
                        .value("Once daily before breakfast"))
                // Other brands are offered only where the doctor allowed one.
                .andExpect(jsonPath("$.items[?(@.name == '%s')].equivalents[*].name".formatted(omeprazole.getName()))
                        .value(omez.getName()))
                .andExpect(jsonPath("$.items[?(@.name == '%s')].equivalents[*]".formatted(cetirizine.getName()),
                        hasSize(0)));

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_log WHERE action = 'MEDICINE_REQUEST_VIEWED' AND entity_id = ?
                """, Integer.class, id.toString())).isEqualTo(1);
    }

    // --- answering ----------------------------------------------------------

    @Test
    @DisplayName("an answer covers every medicine, respects the doctor's substitution choice, and is given once")
    void answerRules() throws Exception {
        UUID id = ask(3000);

        answer(nearOwner, id, """
                {"lines":[{"medicineId":"%s","availability":"YES","unitPrice":5.5}]}
                """.formatted(omeprazole.getId()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INCOMPLETE_ANSWER"));

        // Cetirizine: the doctor did not allow another brand.
        answer(nearOwner, id, lines(line(omeprazole, "YES", null, 5.5, null), line(cetirizine, "YES", null, 2.0, omez)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SUBSTITUTION_NOT_ALLOWED"));

        // Omeprazole may be replaced, but only by the same medicine and strength.
        answer(nearOwner, id, lines(line(omeprazole, "YES", null, 5.5, unrelated), line(cetirizine, "NO", null, null, null)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("NOT_EQUIVALENT"));

        // "Partly" means fewer than asked for.
        answer(nearOwner, id, lines(line(omeprazole, "PARTIAL", 14, 5.5, null), line(cetirizine, "NO", null, null, null)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_QUANTITY"));

        answer(nearOwner, id, lines(line(omeprazole, "YES", null, 4.2, omez), line(cetirizine, "PARTIAL", 3, 2.0, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myStatus").value("ANSWERED"))
                .andExpect(jsonPath("$.myAnswer", hasSize(2)));

        answer(nearOwner, id, allYes(5.0, 2.0))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_ANSWERED"));
    }

    @Test
    @DisplayName("8 tabs of one store answering at once: exactly one answer is kept")
    void concurrentAnswers() throws Exception {
        UUID id = ask(3000);
        List<MedicineRequestService.LineInput> lines = List.of(
                new MedicineRequestService.LineInput(omeprazole.getId(), MedicineRequestService.Availability.YES,
                        null, new BigDecimal("5.50"), null),
                new MedicineRequestService.LineInput(cetirizine.getId(), MedicineRequestService.Availability.NO,
                        null, null, null));

        int wins = race(8, () -> service.answer(nearOwner.getId(), id, null, lines));

        assertThat(wins).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM request_answer_lines WHERE request_id = ?",
                Integer.class, id)).isEqualTo(2);
    }

    @Test
    @DisplayName("once the patient closes a question, stores can no longer answer it or see it queued")
    void closedQuestionsCannotBeAnswered() throws Exception {
        UUID id = ask(3000);
        mvc.perform(post("/api/v1/patients/me/medicine-requests/" + id + "/close")
                        .header("Authorization", bearer(meeraUser)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));

        answer(nearOwner, id, allYes(5.0, 2.0))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUESTION_CLOSED"));
        queue(nearOwner).andExpect(jsonPath("$", hasSize(0)));
    }

    // --- comparing ------------------------------------------------------------

    @Test
    @DisplayName("answers are ranked: everything first, then cheaper, then nearer; silent stores last")
    void comparisonRanking() throws Exception {
        UUID id = ask(3000);
        // Nearer store: everything, dearer. Near store: everything, cheaper
        // (with the allowed other brand). CityCare does not answer.
        answer(nearerOwner, id, allYes(6.0, 3.0)).andExpect(status().isOk());       // 14*6 + 5*3 = 99
        answer(nearOwner, id, lines(line(omeprazole, "YES", null, 4.2, omez), line(cetirizine, "YES", null, 2.0, null)))
                .andExpect(status().isOk());                                          // 14*4.2 + 5*2 = 68.8

        mvc.perform(get("/api/v1/patients/me/medicine-requests/" + id).header("Authorization", bearer(meeraUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores", hasSize(3)))
                .andExpect(jsonPath("$.stores[0].name").value("Sri Sai Medicals"))
                .andExpect(jsonPath("$.stores[0].total").value(68.8))
                .andExpect(jsonPath("$.stores[0].cheapestComplete").value(true))
                .andExpect(jsonPath("$.stores[0].lines[?(@.substituteName)].substituteName").value(omez.getName()))
                .andExpect(jsonPath("$.stores[1].name").value("Green Cross"))
                .andExpect(jsonPath("$.stores[1].nearestComplete").value(true))
                .andExpect(jsonPath("$.stores[2].name").value("CityCare"))
                .andExpect(jsonPath("$.stores[2].answered").value(false));
    }

    @Test
    @DisplayName("a partial store ranks below every store with everything, even a dearer one")
    void completenessBeatsPrice() {
        var partial = Comparison.StoreAnswer.of(UUID.randomUUID(), "Cheap but short", "", "", 100, true, 3, true,
                null, Instant.now(), List.of(line(MedicineRequestService.Availability.PARTIAL, 5, "1.00")), 1);
        var complete = Comparison.StoreAnswer.of(UUID.randomUUID(), "Has it all", "", "", 900, true, 3, true,
                null, Instant.now(), List.of(line(MedicineRequestService.Availability.YES, 10, "9.00")), 1);

        assertThat(Comparison.rank(List.of(partial, complete))).extracting(Comparison.StoreAnswer::name)
                .containsExactly("Has it all", "Cheap but short");
    }

    // --- asking rules -----------------------------------------------------------

    @Test
    @DisplayName("one open question per prescription; closing or expiry frees it")
    void oneOpenQuestionPerPrescription() throws Exception {
        UUID first = ask(3000);
        askRaw(3000, meeraUser, prescriptionId)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_ASKED"));

        // Past its lifetime, the old question no longer blocks a new one.
        jdbc.update("UPDATE medicine_requests SET created_at = now() - interval '7 hours', "
                + "expires_at = now() - interval '1 hour' WHERE id = ?", first);
        UUID second = ask(3000);

        assertThat(second).isNotEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT status FROM medicine_requests WHERE id = ?", String.class, first))
                .isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("you can only ask about your own, current prescription")
    void onlyOwnCurrentPrescriptions() throws Exception {
        askRaw(3000, otherUser, prescriptionId).andExpect(status().isNotFound());

        UUID id = ask(3000);
        mvc.perform(get("/api/v1/patients/me/medicine-requests/" + id).header("Authorization", bearer(otherUser)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/patients/me/medicine-requests/" + id + "/close")
                        .header("Authorization", bearer(meeraUser))).andExpect(status().isOk());

        prescribingService.correct(prescriptionId, doctor.getId(), new PrescriptionDraft("Reflux", null, List.of(
                new PrescriptionDraft.Item(omez.getId(), "20mg", "Once daily", 14, 14, false))));
        askRaw(3000, meeraUser, prescriptionId)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRESCRIPTION_SUPERSEDED"));
    }

    @Test
    @DisplayName("with no verified store in reach, nothing is sent and nothing is saved")
    void noStoresNearby() throws Exception {
        mvc.perform(post("/api/v1/patients/me/medicine-requests").header("Authorization", bearer(meeraUser))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"prescriptionId":"%s","latitude":28.6139,"longitude":77.2090,"radiusM":3000}
                                """.formatted(prescriptionId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_STORES_NEARBY"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM medicine_requests", Integer.class)).isZero();
    }

    @Test
    @DisplayName("asking tells every store asked; an answer tells the patient")
    void notifications() throws Exception {
        UUID id = ask(3000);
        relay.drain();
        assertThat(notificationsFor(nearOwner, "A patient nearby is asking")).isEqualTo(1);
        assertThat(notificationsFor(farOwner, "A patient nearby is asking")).isZero();

        answer(nearOwner, id, allYes(5.0, 2.0)).andExpect(status().isOk());
        relay.drain();
        assertThat(notificationsFor(meeraUser, "Sri Sai Medicals answered")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT body FROM notifications WHERE user_id = ? AND title = ?",
                String.class, meeraUser.getId(), "Sri Sai Medicals answered")).isEqualTo("Has everything you asked for.");
    }

    // --- helpers ----------------------------------------------------------------

    private UUID ask(int radiusM) throws Exception {
        String body = askRaw(radiusM, meeraUser, prescriptionId)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private ResultActions askRaw(int radiusM, User who, UUID rx) throws Exception {
        return mvc.perform(post("/api/v1/patients/me/medicine-requests").header("Authorization", bearer(who))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"prescriptionId":"%s","latitude":%s,"longitude":%s,"radiusM":%d}
                        """.formatted(rx, LAT, LNG, radiusM)));
    }

    private ResultActions queue(User owner) throws Exception {
        return mvc.perform(get("/api/v1/stores/me/requests").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
    }

    private ResultActions answer(User owner, UUID id, String body) throws Exception {
        return mvc.perform(post("/api/v1/stores/me/requests/" + id + "/answer").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String allYes(double omeprazolePrice, double cetirizinePrice) {
        return lines(line(omeprazole, "YES", null, omeprazolePrice, null),
                line(cetirizine, "YES", null, cetirizinePrice, null));
    }

    private static String lines(String... lines) {
        return "{\"lines\":[" + String.join(",", lines) + "]}";
    }

    private static String line(Medicine m, String availability, Integer qty, Double price, Medicine substitute) {
        return """
                {"medicineId":"%s","availability":"%s","quantity":%s,"unitPrice":%s,"substituteMedicineId":%s}
                """.formatted(m.getId(), availability, qty, price,
                substitute == null ? "null" : "\"" + substitute.getId() + "\"");
    }

    private static MedicineRequestService.AnswerLine line(MedicineRequestService.Availability a, int qty, String price) {
        return new MedicineRequestService.AnswerLine(UUID.randomUUID(), a, qty, new BigDecimal(price), null, null, null);
    }

    private List<String> recipients(UUID requestId) {
        return jdbc.queryForList("""
                SELECT s.name FROM request_recipients rr JOIN stores s ON s.id = rr.store_id WHERE rr.request_id = ?
                """, String.class, requestId);
    }

    private int notificationsFor(User user, String title) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND title = ?",
                Integer.class, user.getId(), title);
    }

    /** Releases every contender at once; returns how many succeeded. */
    private static int race(int contenders, Runnable action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    action.run();
                    wins.incrementAndGet();
                } catch (com.medicity.common.ConflictException expected) {
                    // The losers.
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

    private UUID prescribe(Patient patient, List<PrescriptionDraft.Item> items) {
        Instant start = clock.instant().truncatedTo(ChronoUnit.MINUTES).minus(20, ChronoUnit.MINUTES);
        AppointmentSlot slot = slotRepository.save(AppointmentSlot.builder()
                .doctor(doctor).startsAt(start).endsAt(start.plus(30, ChronoUnit.MINUTES)).build());
        Appointment visit = appointmentRepository.save(Appointment.builder().slot(slot).patient(patient)
                .status(AppointmentStatus.BOOKED).reason("Heartburn").scheduledAt(start).build());
        visitService.complete(visit.getId(), doctor.getId());
        return prescribingService.issue(visit.getId(), doctor.getId(),
                new PrescriptionDraft("Reflux", null, items)).getId();
    }

    private Store persistStore(User owner, String name, double lat, double lng, boolean verified) {
        return storeRepository.save(Store.builder()
                .owner(owner).name(name).licenceNumber("RQ-" + UUID.randomUUID().toString().substring(0, 12))
                .phone("+919800000009").addressLine("Somewhere").city("Bengaluru")
                .latitude(BigDecimal.valueOf(lat)).longitude(BigDecimal.valueOf(lng))
                .opensAt(LocalTime.of(8, 0)).closesAt(LocalTime.of(22, 0))
                .verifiedAt(verified ? Instant.now() : null)
                .build());
    }

    private Medicine persistMedicine(String name, String generic, String strength, Medicine.Form form) {
        return medicineRepository.save(Medicine.builder()
                .name(name + "-" + UUID.randomUUID().toString().substring(0, 6))
                .genericName(generic).form(form).strength(strength)
                .unitPrice(new BigDecimal("5.00")).build());
    }

    private Patient persistPatient(User user) {
        return patientRepository.save(Patient.builder().user(user)
                .dateOfBirth(LocalDate.of(1994, 8, 12)).gender(Patient.Gender.FEMALE).build());
    }

    private User persistUser(Role role, String name) {
        User user = User.builder().passwordHash("{noop}irrelevant").fullName(name).role(role).enabled(true).build();
        user.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@request.test");
        return userRepository.save(user);
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.issueAccessToken(user);
    }

    private void cleanUp() {
        jdbc.update("DELETE FROM medicine_requests");
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM stores");
        jdbc.update("DELETE FROM prescription_dispensations");
        prescriptionRepository.deleteAllInBatch();
        appointmentRepository.deleteAll();
        slotRepository.deleteAll();
        patientRepository.deleteAll();
        doctorRepository.deleteAll();
        userRepository.deleteAll();
        jdbc.update("DELETE FROM medicines WHERE generic_name LIKE 'rq-test-%'");
    }
}
