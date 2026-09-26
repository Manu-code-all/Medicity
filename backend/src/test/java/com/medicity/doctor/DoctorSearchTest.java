package com.medicity.doctor;

import com.medicity.patient.PatientRepository;
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
import java.util.UUID;

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

        persistDoctor("dr.rao@medicity.test", "Dr. Anjali Rao", "Cardiology");
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
    @DisplayName("the directory is public — no token required")
    void directoryIsPublic() throws Exception {
        mvc.perform(get("/api/v1/doctors"))
                .andExpect(status().isOk());
    }

    private void persistDoctor(String email, String name, String specialization) {
        User user = User.builder()
                .passwordHash("{noop}irrelevant")
                .fullName(name)
                .role(Role.DOCTOR)
                .enabled(true)
                .build();
        user.setEmail(email);
        user = userRepository.save(user);

        doctorRepository.save(Doctor.builder()
                .user(user)
                .specialization(specialization)
                .licenseNumber("LIC-" + UUID.randomUUID().toString().substring(0, 8))
                .consultationFee(new BigDecimal("1000.00"))
                .yearsExperience(10)
                .build());
    }
}
