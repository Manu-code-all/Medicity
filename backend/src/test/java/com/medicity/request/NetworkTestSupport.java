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
 * A patient with a two-medicine prescription (one where another brand is
 * allowed), and stores around her: two near, one quieter, one far, one not
 * yet verified. Shared by the question and reservation tests.
 */
@AutoConfigureMockMvc
public abstract class NetworkTestSupport extends AbstractIntegrationTest {

    protected static final double LAT = 12.9719;
    protected static final double LNG = 77.6412;

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected UserRepository userRepository;
    @Autowired protected PatientRepository patientRepository;
    @Autowired protected DoctorRepository doctorRepository;
    @Autowired protected SlotRepository slotRepository;
    @Autowired protected AppointmentRepository appointmentRepository;
    @Autowired protected PrescriptionRepository prescriptionRepository;
    @Autowired protected MedicineRepository medicineRepository;
    @Autowired protected StoreRepository storeRepository;
    @Autowired protected VisitService visitService;
    @Autowired protected PrescribingService prescribingService;
    @Autowired protected MedicineRequestService service;
    @Autowired protected OutboxRelay relay;
    @Autowired protected JwtService jwtService;
    @Autowired protected Clock clock;

    protected User meeraUser;
    protected User otherUser;
    protected User doctorUser;
    protected Doctor doctor;
    protected Patient meera;
    protected Medicine omeprazole;
    protected Medicine omez;
    protected Medicine cetirizine;
    protected Medicine unrelated;
    protected UUID prescriptionId;
    private int visits;

    protected User nearOwner;
    protected User nearerOwner;
    protected User quietOwner;
    protected User farOwner;
    protected User unverifiedOwner;
    protected Store near;
    protected Store nearer;

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

    // --- helpers ----------------------------------------------------------------

    protected UUID ask(int radiusM) throws Exception {
        String body = askRaw(radiusM, meeraUser, prescriptionId)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    protected ResultActions askRaw(int radiusM, User who, UUID rx) throws Exception {
        return mvc.perform(post("/api/v1/patients/me/medicine-requests").header("Authorization", bearer(who))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"prescriptionId":"%s","latitude":%s,"longitude":%s,"radiusM":%d}
                        """.formatted(rx, LAT, LNG, radiusM)));
    }

    protected ResultActions queue(User owner) throws Exception {
        return mvc.perform(get("/api/v1/stores/me/requests").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
    }

    protected ResultActions answer(User owner, UUID id, String body) throws Exception {
        return mvc.perform(post("/api/v1/stores/me/requests/" + id + "/answer").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected String allYes(double omeprazolePrice, double cetirizinePrice) {
        return lines(line(omeprazole, "YES", null, omeprazolePrice, null),
                line(cetirizine, "YES", null, cetirizinePrice, null));
    }

    protected static String lines(String... lines) {
        return "{\"lines\":[" + String.join(",", lines) + "]}";
    }

    protected static String line(Medicine m, String availability, Integer qty, Double price, Medicine substitute) {
        return """
                {"medicineId":"%s","availability":"%s","quantity":%s,"unitPrice":%s,"substituteMedicineId":%s}
                """.formatted(m.getId(), availability, qty, price,
                substitute == null ? "null" : "\"" + substitute.getId() + "\"");
    }

    protected static MedicineRequestService.AnswerLine line(MedicineRequestService.Availability a, int qty, String price) {
        return new MedicineRequestService.AnswerLine(UUID.randomUUID(), a, qty, new BigDecimal(price), null, null, null);
    }

    protected List<String> recipients(UUID requestId) {
        return jdbc.queryForList("""
                SELECT s.name FROM request_recipients rr JOIN stores s ON s.id = rr.store_id WHERE rr.request_id = ?
                """, String.class, requestId);
    }

    protected int notificationsFor(User user, String title) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND title = ?",
                Integer.class, user.getId(), title);
    }

    /** One contender in a race; {@code i} is its number, so contenders can differ. */
    @FunctionalInterface
    protected interface Contender {
        void run(int i) throws Exception;
    }

    /** Releases every contender at once; returns how many succeeded. Conflicts are the losers. */
    protected static int race(int contenders, Contender action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    action.run(n);
                    wins.incrementAndGet();
                } catch (com.medicity.common.ConflictException expected) {
                    // A loser.
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

    protected UUID prescribe(Patient patient, List<PrescriptionDraft.Item> items) {
        // Each visit an hour before the last: one doctor's slots must not overlap.
        Instant start = clock.instant().truncatedTo(ChronoUnit.MINUTES).minus(20 + 60L * visits++, ChronoUnit.MINUTES);
        AppointmentSlot slot = slotRepository.save(AppointmentSlot.builder()
                .doctor(doctor).startsAt(start).endsAt(start.plus(30, ChronoUnit.MINUTES)).build());
        Appointment visit = appointmentRepository.save(Appointment.builder().slot(slot).patient(patient)
                .status(AppointmentStatus.BOOKED).reason("Heartburn").scheduledAt(start).build());
        visitService.complete(visit.getId(), doctor.getId());
        return prescribingService.issue(visit.getId(), doctor.getId(),
                new PrescriptionDraft("Reflux", null, items)).getId();
    }

    protected Store persistStore(User owner, String name, double lat, double lng, boolean verified) {
        return storeRepository.save(Store.builder()
                .owner(owner).name(name).licenceNumber("RQ-" + UUID.randomUUID().toString().substring(0, 12))
                .phone("+919800000009").addressLine("Somewhere").city("Bengaluru")
                .latitude(BigDecimal.valueOf(lat)).longitude(BigDecimal.valueOf(lng))
                .opensAt(LocalTime.of(8, 0)).closesAt(LocalTime.of(22, 0))
                .verifiedAt(verified ? Instant.now() : null)
                .build());
    }

    protected Medicine persistMedicine(String name, String generic, String strength, Medicine.Form form) {
        return medicineRepository.save(Medicine.builder()
                .name(name + "-" + UUID.randomUUID().toString().substring(0, 6))
                .genericName(generic).form(form).strength(strength)
                .unitPrice(new BigDecimal("5.00")).build());
    }

    protected Patient persistPatient(User user) {
        return patientRepository.save(Patient.builder().user(user)
                .dateOfBirth(LocalDate.of(1994, 8, 12)).gender(Patient.Gender.FEMALE).build());
    }

    protected User persistUser(Role role, String name) {
        User user = User.builder().passwordHash("{noop}irrelevant").fullName(name).role(role).enabled(true).build();
        user.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@request.test");
        return userRepository.save(user);
    }

    protected String bearer(User user) {
        return "Bearer " + jwtService.issueAccessToken(user);
    }

    protected void cleanUp() {
        jdbc.update("DELETE FROM medicine_requests");
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM stores");
        jdbc.update("DELETE FROM prescription_dispensations");
        prescriptionRepository.deleteAllInBatch();
        jdbc.update("DELETE FROM prescription_scans");
        appointmentRepository.deleteAll();
        slotRepository.deleteAll();
        patientRepository.deleteAll();
        doctorRepository.deleteAll();
        userRepository.deleteAll();
        jdbc.update("DELETE FROM medicines WHERE generic_name LIKE 'rq-test-%'");
    }
}
