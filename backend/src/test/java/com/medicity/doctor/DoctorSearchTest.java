package com.medicity.doctor;

import com.medicity.patient.Patient;
import com.medicity.patient.PatientRepository;
import com.medicity.scheduling.Appointment;
import com.medicity.scheduling.AppointmentSlot;
import com.medicity.scheduling.AppointmentStatus;
import com.medicity.scheduling.AppointmentRepository;
import com.medicity.scheduling.SlotRepository;
import com.medicity.support.AbstractIntegrationTest;
import com.medicity.user.Role;
import com.medicity.user.User;
import com.medicity.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Doctor directory search, through the real HTTP stack and a real database.
 *
 * <p>This class exists because the endpoint shipped broken. With no filters
 * supplied, the optional parameters reach Hibernate as null, the PostgreSQL
 * driver sends them untyped, and the server resolves {@code lower(?)} as
 * {@code lower(bytea)} — which does not exist. The first request in production
 * returned 500. No earlier test exercised this path.
 *
 * <p>The unfiltered case is the one that matters: it is the landing page of the
 * frontend, and it is the only case in which every optional parameter is null.
 */
@AutoConfigureMockMvc
@DisplayName("Doctor directory search")
class DoctorSearchTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private Doctor rao;

    @BeforeEach
    void setUp() {
        // Children before parents. The container is shared across test classes,
        // so rows left by other suites are still here. Deleting doctors cascades
        // to slots, but appointments reference slots WITHOUT cascade, so skipping
        // this ordering fails with an FK violation that depends on test order.
        appointmentRepository.deleteAll();
        slotRepository.deleteAll();
        patientRepository.deleteAll();
        doctorRepository.deleteAll();
        userRepository.deleteAll();

        rao = persistDoctor("dr.rao@medicity.test", "Dr. Anjali Rao", "Cardiology");
        persistDoctor("dr.iyer@medicity.test", "Dr. Suresh Iyer", "Neurology");
        persistDoctor("dr.khan@medicity.test", "Dr. Farah Khan", "Cardiology");
    }

    @Test
    @DisplayName("no filters: returns every doctor (the regression — every parameter is null)")
    void unfilteredSearchReturnsEveryone() throws Exception {
        mvc.perform(get("/api/v1/doctors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)));
    }

    @Test
    @DisplayName("specialization filter is case-insensitive")
    void filtersBySpecialization() throws Exception {
        mvc.perform(get("/api/v1/doctors").param("specialization", "cardiology"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)));
    }

    @Test
    @DisplayName("name filter matches a substring, case-insensitively")
    void filtersByNameSubstring() throws Exception {
        mvc.perform(get("/api/v1/doctors").param("q", "iyer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].fullName").value("Dr. Suresh Iyer"));
    }

    @Test
    @DisplayName("one filter supplied, the other null — the mixed case")
    void oneFilterNullOneSet() throws Exception {
        // Only the name is given, so specialization is null while nameQuery is
        // not. Each parameter must be typed independently for this to work.
        mvc.perform(get("/api/v1/doctors").param("q", "khan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    @DisplayName("blank parameters are treated as absent, not as a match-nothing filter")
    void blankParametersAreIgnored() throws Exception {
        mvc.perform(get("/api/v1/doctors").param("specialization", "   ").param("q", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)));
    }

    @Test
    @DisplayName("q also matches the specialisation, so typing 'cardio' finds cardiologists")
    void queryMatchesSpecialisation() throws Exception {
        mvc.perform(get("/api/v1/doctors").param("q", "CARDIO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)));
    }

    @Test
    @DisplayName("specialisations are listed once each, with how many doctors practise them")
    void listsSpecialties() throws Exception {
        mvc.perform(get("/api/v1/doctors/specialties"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("Cardiology"))
                .andExpect(jsonPath("$[0].doctors").value(2))
                .andExpect(jsonPath("$[1].name").value("Neurology"))
                .andExpect(jsonPath("$[1].doctors").value(1));
    }

    @Test
    @DisplayName("suggestions: matching specialisations, then doctors by name or specialisation")
    void suggests() throws Exception {
        mvc.perform(get("/api/v1/doctors/suggest").param("q", "neuro"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specialties", hasSize(1)))
                .andExpect(jsonPath("$.specialties[0].name").value("Neurology"))
                .andExpect(jsonPath("$.doctors", hasSize(1)))
                .andExpect(jsonPath("$.doctors[0].fullName").value("Dr. Suresh Iyer"));

        mvc.perform(get("/api/v1/doctors/suggest").param("q", "rao"))
                .andExpect(jsonPath("$.specialties", hasSize(0)))
                .andExpect(jsonPath("$.doctors[0].fullName").value("Dr. Anjali Rao"));

        // One character lists nobody, rather than everyone.
        mvc.perform(get("/api/v1/doctors/suggest").param("q", "a"))
                .andExpect(jsonPath("$.specialties", hasSize(0)))
                .andExpect(jsonPath("$.doctors", hasSize(0)));
    }

    @Test
    @DisplayName("a disabled doctor is neither listed nor counted")
    void disabledDoctorsHidden() throws Exception {
        User khan = userRepository.findByEmail("dr.khan@medicity.test").orElseThrow();
        khan.setEnabled(false);
        userRepository.save(khan);

        mvc.perform(get("/api/v1/doctors/specialties"))
                .andExpect(jsonPath("$[0].doctors").value(1));
        mvc.perform(get("/api/v1/doctors/suggest").param("q", "khan"))
                .andExpect(jsonPath("$.doctors", hasSize(0)));
    }

    @Test
    @DisplayName("each card carries its doctor's next three bookable times: not too soon, not taken")
    void cardsCarryNextSlots() throws Exception {
        Instant hour = Instant.now().truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.DAYS);
        slot(rao, Instant.now().plus(10, ChronoUnit.MINUTES));  // inside the 30-minute notice: not offered
        AppointmentSlot taken = slot(rao, hour);
        User someone = User.builder().passwordHash("{noop}x").fullName("Meera Nair").role(Role.PATIENT).enabled(true).build();
        someone.setEmail("meera@medicity.test");
        Patient meera = patientRepository.save(Patient.builder().user(userRepository.save(someone))
                .dateOfBirth(LocalDate.of(1993, 4, 1)).gender(Patient.Gender.FEMALE).build());
        appointmentRepository.save(Appointment.builder().slot(taken).patient(meera)
                .status(AppointmentStatus.BOOKED).scheduledAt(hour).build());
        for (int h = 1; h <= 4; h++) {
            slot(rao, hour.plus(h, ChronoUnit.HOURS));
        }

        mvc.perform(get("/api/v1/doctors").param("q", "Rao"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].nextSlots", hasSize(3)))
                .andExpect(jsonPath("$.content[0].nextSlots[*].startsAt", contains(
                        hour.plus(1, ChronoUnit.HOURS).toString(),
                        hour.plus(2, ChronoUnit.HOURS).toString(),
                        hour.plus(3, ChronoUnit.HOURS).toString())));
        mvc.perform(get("/api/v1/doctors").param("q", "Iyer"))
                .andExpect(jsonPath("$.content[0].nextSlots", hasSize(0)));
    }

    private AppointmentSlot slot(Doctor doctor, Instant start) {
        return slotRepository.save(AppointmentSlot.builder()
                .doctor(doctor).startsAt(start).endsAt(start.plus(30, ChronoUnit.MINUTES)).build());
    }

    @Test
    @DisplayName("insurance filters the directory; each card lists its insurers and prices, every-visit charges first")
    void insuranceAndPrices() throws Exception {
        jdbc.update("INSERT INTO doctor_insurance (doctor_id, insurer) VALUES (?, 'Star Health'), (?, 'CGHS')",
                rao.getId(), rao.getId());
        jdbc.update("""
                INSERT INTO doctor_procedure_prices (doctor_id, procedure, price_inr, every_visit)
                VALUES (?, 'ECG', 350, FALSE), (?, 'Registration', 100, TRUE)
                """, rao.getId(), rao.getId());

        mvc.perform(get("/api/v1/doctors").param("insurance", "Star Health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].fullName").value("Dr. Anjali Rao"))
                .andExpect(jsonPath("$.content[0].insurers", contains("CGHS", "Star Health")))
                .andExpect(jsonPath("$.content[0].prices[0].procedure").value("Registration"))
                .andExpect(jsonPath("$.content[0].prices[0].everyVisit").value(true))
                .andExpect(jsonPath("$.content[1]").doesNotExist());
        mvc.perform(get("/api/v1/doctors").param("insurance", "Niva Bupa"))
                .andExpect(jsonPath("$.content", hasSize(0)));
        mvc.perform(get("/api/v1/doctors").param("insurance", "Star Health").param("specialization", "Neurology"))
                .andExpect(jsonPath("$.content", hasSize(0)));
        mvc.perform(get("/api/v1/doctors"))
                .andExpect(jsonPath("$.content", hasSize(3)));
        mvc.perform(get("/api/v1/doctors/insurers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("PRIVATE"))
                .andExpect(jsonPath("$[?(@.name == 'Ayushman Bharat (PM-JAY)')].kind").value("GOVERNMENT"));
    }

    @Test
    @DisplayName("the directory is public — no token required")
    void directoryIsPublic() throws Exception {
        mvc.perform(get("/api/v1/doctors"))
                .andExpect(status().isOk());
    }

    private Doctor persistDoctor(String email, String name, String specialization) {
        User user = User.builder()
                .passwordHash("{noop}irrelevant")
                .fullName(name)
                .role(Role.DOCTOR)
                .enabled(true)
                .build();
        user.setEmail(email);
        user = userRepository.save(user);

        return doctorRepository.save(Doctor.builder()
                .user(user)
                .specialization(specialization)
                .licenseNumber("LIC-" + UUID.randomUUID().toString().substring(0, 8))
                .consultationFee(new BigDecimal("1000.00"))
                .yearsExperience(10)
                .build());
    }
}
