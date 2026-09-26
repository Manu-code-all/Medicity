package com.medicity.patient;

import com.medicity.clinical.Prescription;
import com.medicity.clinical.PrescriptionItem;
import com.medicity.clinical.PrescriptionRepository;
import com.medicity.doctor.Doctor;
import com.medicity.doctor.DoctorRepository;
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
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The patient portal, through the real HTTP stack.
 *
 * <p>Two properties matter most. First, isolation: the routes take no patient
 * id, so the only records a caller can reach are their own — verified here by
 * giving a second patient a history and checking none of it leaks. Second, the
 * history is presented correctly: upcoming and past partition every visit with
 * no overlap, and a corrected prescription replaces the original rather than
 * appearing beside it.
 */
@AutoConfigureMockMvc
@DisplayName("Patient portal")
class PatientPortalTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired PrescriptionRepository prescriptionRepository;
    @Autowired MedicineRepository medicineRepository;
    @Autowired JwtService jwtService;
    @Autowired Clock clock;

    private String meeraToken;
    private String otherPatientToken;
    private String doctorToken;

    private Doctor doctor;
    private Medicine paracetamol;
    private Instant now;

    @BeforeEach
    void setUp() {
        cleanUp();
        now = clock.instant().truncatedTo(ChronoUnit.HOURS);

        User meeraUser = persistUser("meera@medicity.test", "Meera Nair", Role.PATIENT);
        Patient meera = persistPatient(meeraUser);
        meeraToken = jwtService.issueAccessToken(meeraUser);

        User otherUser = persistUser("ravi@medicity.test", "Ravi Kumar", Role.PATIENT);
        Patient other = persistPatient(otherUser);
        otherPatientToken = jwtService.issueAccessToken(otherUser);

        User doctorUser = persistUser("dr.rao@medicity.test", "Dr. Anjali Rao", Role.DOCTOR);
        doctor = doctorRepository.save(Doctor.builder()
                .user(doctorUser)
                .specialization("Cardiology")
                .licenseNumber("LIC-" + UUID.randomUUID().toString().substring(0, 8))
                .consultationFee(new BigDecimal("1200.00"))
                .yearsExperience(14)
                .build());
        doctorToken = jwtService.issueAccessToken(doctorUser);

        paracetamol = medicineRepository.save(Medicine.builder()
                .name("Paracetamol-" + UUID.randomUUID().toString().substring(0, 6))
                .genericName("Acetaminophen")
                .form(Medicine.Form.TABLET)
                .strength("500mg")
                .unitPrice(new BigDecimal("40.00"))
                .build());

        // Meera: two upcoming, and a past of one completed, one cancelled, and
        // one still BOOKED but already in the past — which belongs to history.
        persistVisit(meera, now.plus(5, ChronoUnit.DAYS), AppointmentStatus.BOOKED);
        persistVisit(meera, now.plus(2, ChronoUnit.DAYS), AppointmentStatus.BOOKED);
        Appointment completed = persistVisit(meera, now.minus(40, ChronoUnit.DAYS), AppointmentStatus.COMPLETED);
        persistVisit(meera, now.minus(20, ChronoUnit.DAYS), AppointmentStatus.CANCELLED);
        persistVisit(meera, now.minus(3, ChronoUnit.DAYS), AppointmentStatus.BOOKED);

        // A prescription, then a correction of it issued an hour later.
        Prescription original = persistPrescription(completed, meera, null,
                completed.getScheduledAt().plus(20, ChronoUnit.MINUTES), "Three times daily");
        persistPrescription(completed, meera, original.getId(),
                completed.getScheduledAt().plus(80, ChronoUnit.MINUTES), "As needed");

        // Ravi has history too, so a leak would be visible rather than vacuous.
        Appointment raviVisit = persistVisit(other, now.minus(10, ChronoUnit.DAYS), AppointmentStatus.COMPLETED);
        persistPrescription(raviVisit, other, null, raviVisit.getScheduledAt(), "Twice daily");
    }

    @AfterEach
    void tearDown() {
        // Other suites delete appointments in their setUp; prescriptions
        // reference appointments, so leaving them behind would break those
        // suites with an FK violation that depends on test order.
        cleanUp();
    }

    @Test
    @DisplayName("summary counts the caller's visits and names the next one")
    void summary() throws Exception {
        mvc.perform(get("/api/v1/patients/me/summary").header("Authorization", "Bearer " + meeraToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upcoming").value(2))
                .andExpect(jsonPath("$.completed").value(1))
                .andExpect(jsonPath("$.cancelled").value(1))
                .andExpect(jsonPath("$.prescriptions").value(1))
                .andExpect(jsonPath("$.nextVisit.scheduledAt").value(now.plus(2, ChronoUnit.DAYS).toString()))
                .andExpect(jsonPath("$.nextVisit.doctorName").value("Dr. Anjali Rao"));
    }

    @Test
    @DisplayName("upcoming and past partition every visit: soonest-first, then newest-first")
    void upcomingAndPastPartitionHistory() throws Exception {
        mvc.perform(get("/api/v1/patients/me/appointments").param("scope", "upcoming")
                        .header("Authorization", "Bearer " + meeraToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].scheduledAt").value(now.plus(2, ChronoUnit.DAYS).toString()))
                .andExpect(jsonPath("$.content[0].specialization").value("Cardiology"));

        mvc.perform(get("/api/v1/patients/me/appointments").param("scope", "past")
                        .header("Authorization", "Bearer " + meeraToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)))
                // The stale BOOKED visit lands in history, newest first.
                .andExpect(jsonPath("$.content[0].status").value("BOOKED"))
                .andExpect(jsonPath("$.content[1].status").value("CANCELLED"))
                .andExpect(jsonPath("$.content[2].status").value("COMPLETED"));
    }

    @Test
    @DisplayName("a corrected prescription replaces the original instead of appearing beside it")
    void supersededPrescriptionIsHidden() throws Exception {
        mvc.perform(get("/api/v1/patients/me/prescriptions").header("Authorization", "Bearer " + meeraToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].revised").value(true))
                .andExpect(jsonPath("$[0].doctorName").value("Dr. Anjali Rao"))
                .andExpect(jsonPath("$[0].items", hasSize(1)))
                .andExpect(jsonPath("$[0].items[0].frequency").value("As needed"));
    }

    @Test
    @DisplayName("each patient sees only their own records")
    void recordsAreIsolatedPerPatient() throws Exception {
        mvc.perform(get("/api/v1/patients/me/prescriptions").header("Authorization", "Bearer " + otherPatientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].items[0].frequency").value("Twice daily"));

        mvc.perform(get("/api/v1/patients/me/appointments").param("scope", "past")
                        .header("Authorization", "Bearer " + otherPatientToken))
                .andExpect(jsonPath("$.content", hasSize(1)));

        mvc.perform(get("/api/v1/patients/me").header("Authorization", "Bearer " + otherPatientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.email").value("ravi@medicity.test"));
    }

    @Test
    @DisplayName("the portal is for patients: anonymous gets 401, a doctor gets 403")
    void portalRequiresPatientRole() throws Exception {
        mvc.perform(get("/api/v1/patients/me/summary"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/patients/me/summary").header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an unknown scope is a client error, not a 500")
    void unknownScopeIsRejected() throws Exception {
        mvc.perform(get("/api/v1/patients/me/appointments").param("scope", "everything")
                        .header("Authorization", "Bearer " + meeraToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_SCOPE"));
    }

    // --- fixtures -------------------------------------------------------

    private void cleanUp() {
        // Bulk JPQL delete; the database cascades prescription_items.
        prescriptionRepository.deleteAllInBatch();
        appointmentRepository.deleteAll();
        slotRepository.deleteAll();
        patientRepository.deleteAll();
        doctorRepository.deleteAll();
        userRepository.deleteAll();
        if (paracetamol != null) {
            medicineRepository.deleteById(paracetamol.getId());
            paracetamol = null;
        }
    }

    private Appointment persistVisit(Patient patient, Instant at, AppointmentStatus status) {
        // Every fixture visit is on a different day, so slots never overlap.
        Instant start = at;
        AppointmentSlot slot = slotRepository.save(AppointmentSlot.builder()
                .doctor(doctor)
                .startsAt(start)
                .endsAt(start.plus(30, ChronoUnit.MINUTES))
                .build());
        // Written directly rather than through BookingService, which rightly
        // refuses to book a slot in the past.
        Appointment appointment = Appointment.builder()
                .slot(slot)
                .patient(patient)
                .status(status == AppointmentStatus.CANCELLED ? AppointmentStatus.BOOKED : status)
                .reason("Check-up")
                .scheduledAt(start)
                .build();
        if (status == AppointmentStatus.CANCELLED) {
            appointment.cancel(start.minus(1, ChronoUnit.DAYS), "Rescheduled");
        }
        return appointmentRepository.save(appointment);
    }

    private Prescription persistPrescription(Appointment appointment, Patient patient, UUID supersedes,
                                             Instant issuedAt, String frequency) {
        Prescription prescription = Prescription.builder()
                .appointment(appointment)
                .doctor(doctor)
                .patient(patient)
                .diagnosis("Tension-type headache")
                .supersedesId(supersedes)
                .issuedAt(issuedAt)
                .build();
        prescription.getItems().add(PrescriptionItem.builder()
                .prescription(prescription)
                .medicine(paracetamol)
                .dosage("500mg")
                .frequency(frequency)
                .durationDays(5)
                .quantity(10)
                .build());
        return prescriptionRepository.save(prescription);
    }

    private User persistUser(String email, String name, Role role) {
        User user = User.builder()
                .passwordHash("{noop}irrelevant")
                .fullName(name)
                .role(role)
                .enabled(true)
                .build();
        user.setEmail(email);
        return userRepository.save(user);
    }

    private Patient persistPatient(User user) {
        return patientRepository.save(Patient.builder()
                .user(user)
                .dateOfBirth(LocalDate.of(1994, 8, 12))
                .gender(Patient.Gender.FEMALE)
                .build());
    }
}
