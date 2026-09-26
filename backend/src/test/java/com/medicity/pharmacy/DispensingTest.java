package com.medicity.pharmacy;

import com.medicity.clinical.Prescription;
import com.medicity.clinical.PrescriptionItem;
import com.medicity.clinical.PrescriptionRepository;
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
import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dispensing a prescription: at most once, all or nothing, never below zero.
 */
@AutoConfigureMockMvc
@DisplayName("Dispensing")
class DispensingTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DispensingService dispensingService;
    @Autowired UserRepository userRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired PrescriptionRepository prescriptionRepository;
    @Autowired MedicineRepository medicineRepository;
    @Autowired MedicineStockRepository stockRepository;
    @Autowired JwtService jwtService;
    @Autowired Clock clock;

    private User adminUser;
    private User patientUser;
    private User doctorUser;
    private Patient patient;
    private Doctor doctor;
    private Appointment visit;
    private Medicine paracetamol;
    private Medicine omeprazole;
    private final List<UUID> createdMedicines = new ArrayList<>();

    @BeforeEach
    void setUp() {
        cleanUp();

        adminUser = persistUser("Pharmacy Desk", Role.ADMIN);
        patientUser = persistUser("Meera Nair", Role.PATIENT);
        patient = patientRepository.save(Patient.builder()
                .user(patientUser).dateOfBirth(LocalDate.of(1994, 8, 12)).gender(Patient.Gender.FEMALE).build());
        doctorUser = persistUser("Dr. Suresh Iyer", Role.DOCTOR);
        doctor = doctorRepository.save(Doctor.builder()
                .user(doctorUser).specialization("Neurology")
                .licenseNumber("LIC-" + UUID.randomUUID().toString().substring(0, 8))
                .consultationFee(new BigDecimal("1500.00")).yearsExperience(18).build());

        Instant start = clock.instant().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        AppointmentSlot slot = slotRepository.save(AppointmentSlot.builder()
                .doctor(doctor).startsAt(start).endsAt(start.plus(30, ChronoUnit.MINUTES)).build());
        visit = appointmentRepository.save(Appointment.builder()
                .slot(slot).patient(patient).status(AppointmentStatus.COMPLETED)
                .reason("Headaches").scheduledAt(start).build());

        paracetamol = persistMedicine("Paracetamol", "500mg", 100);
        omeprazole = persistMedicine("Omeprazole", "20mg", 100);
    }

    @AfterEach
    void tearDown() {
        // Prescriptions and dispensations reference appointments and users,
        // which other suites delete in their own setUp.
        cleanUp();
    }

    @Test
    @DisplayName("dispensing takes every medicine off stock and records a movement for each")
    void dispensesEveryItem() throws Exception {
        Prescription rx = persistPrescription(null, item(paracetamol, 15), item(omeprazole, 28));

        mvc.perform(post("/api/v1/pharmacy/prescriptions/" + rx.getId() + "/dispense")
                        .header("Authorization", bearer(adminUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));

        assertThat(onHand(paracetamol)).isEqualTo(85);
        assertThat(onHand(omeprazole)).isEqualTo(72);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM stock_movements WHERE reference_id = ? AND reason = 'DISPENSE'",
                Integer.class, rx.getId())).isEqualTo(2);
    }

    @Test
    @DisplayName("a second dispense is refused and moves no stock")
    void cannotDispenseTwice() throws Exception {
        Prescription rx = persistPrescription(null, item(paracetamol, 10));

        mvc.perform(post("/api/v1/pharmacy/prescriptions/" + rx.getId() + "/dispense")
                        .header("Authorization", bearer(adminUser)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/pharmacy/prescriptions/" + rx.getId() + "/dispense")
                        .header("Authorization", bearer(adminUser)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_DISPENSED"));

        assertThat(onHand(paracetamol)).isEqualTo(90);
    }

    @Test
    @DisplayName("16 counters dispensing the same prescription at once: exactly one succeeds")
    void concurrentDispenseHappensOnce() throws Exception {
        Prescription rx = persistPrescription(null, item(paracetamol, 5));
        int counters = 16;

        ExecutorService pool = Executors.newFixedThreadPool(counters);
        CountDownLatch ready = new CountDownLatch(counters);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < counters; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    dispensingService.dispense(rx.getId(), adminUser.getId());
                    succeeded.incrementAndGet();
                } catch (com.medicity.common.ConflictException e) {
                    assertThat(e.getCode()).isEqualTo("ALREADY_DISPENSED");
                    refused.incrementAndGet();
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

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(refused.get()).isEqualTo(counters - 1);
        assertThat(onHand(paracetamol)).as("stock taken exactly once").isEqualTo(95);
    }

    @Test
    @DisplayName("if one medicine is short, nothing is dispensed")
    void allOrNothing() throws Exception {
        Prescription rx = persistPrescription(null, item(paracetamol, 10), item(omeprazole, 500));

        mvc.perform(post("/api/v1/pharmacy/prescriptions/" + rx.getId() + "/dispense")
                        .header("Authorization", bearer(adminUser)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Omeprazole")));

        assertThat(onHand(paracetamol)).as("the decrement that succeeded was rolled back").isEqualTo(100);
        assertThat(onHand(omeprazole)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM prescription_dispensations WHERE prescription_id = ?",
                Integer.class, rx.getId())).as("no dispensation recorded").isZero();
    }

    @Test
    @DisplayName("a superseded prescription cannot be dispensed; its correction can")
    void supersededIsRefused() throws Exception {
        Prescription original = persistPrescription(null, item(paracetamol, 15));
        Prescription correction = persistPrescription(original.getId(), item(paracetamol, 10));

        mvc.perform(post("/api/v1/pharmacy/prescriptions/" + original.getId() + "/dispense")
                        .header("Authorization", bearer(adminUser)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRESCRIPTION_SUPERSEDED"));

        mvc.perform(post("/api/v1/pharmacy/prescriptions/" + correction.getId() + "/dispense")
                        .header("Authorization", bearer(adminUser)))
                .andExpect(status().isOk());
        assertThat(onHand(paracetamol)).isEqualTo(90);
    }

    @Test
    @DisplayName("only an admin can dispense; the patient sees it in their portal afterwards")
    void dispenseIsAdminOnlyAndVisibleToPatient() throws Exception {
        Prescription rx = persistPrescription(null, item(paracetamol, 10));

        mvc.perform(post("/api/v1/pharmacy/prescriptions/" + rx.getId() + "/dispense")
                        .header("Authorization", bearer(patientUser)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/pharmacy/prescriptions/" + rx.getId() + "/dispense")
                        .header("Authorization", bearer(doctorUser)))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/patients/me/prescriptions").header("Authorization", bearer(patientUser)))
                .andExpect(jsonPath("$[0].dispensedAt").doesNotExist());

        mvc.perform(post("/api/v1/pharmacy/prescriptions/" + rx.getId() + "/dispense")
                        .header("Authorization", bearer(adminUser)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/patients/me/prescriptions").header("Authorization", bearer(patientUser)))
                .andExpect(jsonPath("$[0].dispensedAt").exists());
    }

    @Test
    @DisplayName("restocking adds stock, is audited, and clears the low-stock flag")
    void restock() throws Exception {
        Medicine scarce = persistMedicine("Cetirizine", "10mg", 3);

        mvc.perform(get("/api/v1/pharmacy/stock/low").header("Authorization", bearer(adminUser)))
                .andExpect(jsonPath("$[?(@.medicineId == '" + scarce.getId() + "')]").exists());

        mvc.perform(post("/api/v1/pharmacy/medicines/" + scarce.getId() + "/restock")
                        .header("Authorization", bearer(adminUser))
                        .contentType("application/json")
                        .content("{\"quantity\": 200}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityOnHand").value(203))
                .andExpect(jsonPath("$.lowStock").value(false));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'STOCK_RESTOCKED' AND entity_id = ?",
                Integer.class, scarce.getId().toString())).isEqualTo(1);
    }

    // --- fixtures -------------------------------------------------------

    private void cleanUp() {
        jdbc.update("DELETE FROM prescription_dispensations");
        prescriptionRepository.deleteAllInBatch();
        appointmentRepository.deleteAll();
        slotRepository.deleteAll();
        patientRepository.deleteAll();
        doctorRepository.deleteAll();
        userRepository.deleteAll();
        for (UUID id : createdMedicines) {
            jdbc.update("DELETE FROM stock_movements WHERE medicine_id = ?", id);
            jdbc.update("DELETE FROM medicine_stock WHERE medicine_id = ?", id);
            jdbc.update("DELETE FROM medicines WHERE id = ?", id);
        }
        createdMedicines.clear();
    }

    private record Line(Medicine medicine, int quantity) {}

    private static Line item(Medicine medicine, int quantity) {
        return new Line(medicine, quantity);
    }

    private Prescription persistPrescription(UUID supersedes, Line... lines) {
        Prescription rx = Prescription.builder()
                .appointment(visit).doctor(doctor).patient(patient)
                .diagnosis("Tension-type headache")
                .supersedesId(supersedes)
                .issuedAt(clock.instant())
                .build();
        for (Line line : lines) {
            rx.getItems().add(PrescriptionItem.builder()
                    .prescription(rx).medicine(line.medicine())
                    .dosage("1 tablet").frequency("Twice daily").durationDays(5).quantity(line.quantity())
                    .build());
        }
        return prescriptionRepository.save(rx);
    }

    private Medicine persistMedicine(String name, String strength, int onHand) {
        Medicine m = medicineRepository.save(Medicine.builder()
                .name(name + "-" + UUID.randomUUID().toString().substring(0, 6))
                .genericName(name).form(Medicine.Form.TABLET).strength(strength)
                .unitPrice(new BigDecimal("40.00")).build());
        createdMedicines.add(m.getId());
        jdbc.update("INSERT INTO medicine_stock (medicine_id, quantity_on_hand, reorder_level) VALUES (?, ?, 10)",
                m.getId(), onHand);
        return m;
    }

    private int onHand(Medicine m) {
        return jdbc.queryForObject("SELECT quantity_on_hand FROM medicine_stock WHERE medicine_id = ?",
                Integer.class, m.getId());
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.issueAccessToken(user);
    }

    private User persistUser(String name, Role role) {
        User user = User.builder().passwordHash("{noop}irrelevant").fullName(name).role(role).enabled(true).build();
        user.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@medicity.test");
        return userRepository.save(user);
    }
}
