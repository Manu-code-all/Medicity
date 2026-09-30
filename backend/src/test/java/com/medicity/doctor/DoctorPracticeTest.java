package com.medicity.doctor;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A doctor keeps their fee, bio, insurers and prices current; the directory shows the change. */
@AutoConfigureMockMvc
@DisplayName("Doctor's practice details")
class DoctorPracticeTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;

    private User raoUser;
    private Doctor rao;

    @BeforeEach
    void setUp() {
        appointmentRepository.deleteAll();
        slotRepository.deleteAll();
        patientRepository.deleteAll();
        doctorRepository.deleteAll();
        userRepository.deleteAll();
        raoUser = User.builder().passwordHash("{noop}x").fullName("Dr. Anjali Rao").role(Role.DOCTOR).enabled(true).build();
        raoUser.setEmail("rao-" + UUID.randomUUID() + "@practice.test");
        raoUser = userRepository.save(raoUser);
        rao = doctorRepository.save(Doctor.builder().user(raoUser).specialization("Cardiology")
                .licenseNumber("LIC-" + UUID.randomUUID().toString().substring(0, 8))
                .consultationFee(new BigDecimal("900.00")).yearsExperience(10).build());
    }

    @Test
    @DisplayName("saving replaces fee, bio, insurers and prices, and the directory shows them at once")
    void updateShowsInDirectory() throws Exception {
        practice().andExpect(status().isOk())
                .andExpect(jsonPath("$.consultationFee").value(900.00))
                .andExpect(jsonPath("$.insurers", hasSize(0)))
                .andExpect(jsonPath("$.availableInsurers[?(@.name == 'Star Health')]").exists());

        save("""
                {"consultationFee":1100,"bio":"  Heart rhythm clinic.  ","yearsExperience":11,
                 "insurers":["Star Health","Ayushman Bharat (PM-JAY)"],
                 "prices":[{"procedure":"ECG","priceInr":300,"everyVisit":false},
                           {"procedure":"Registration","priceInr":100,"everyVisit":true}]}
                """).andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").value("Heart rhythm clinic."))
                .andExpect(jsonPath("$.insurers", contains("Ayushman Bharat (PM-JAY)", "Star Health")));

        mvc.perform(get("/api/v1/doctors").param("insurance", "Star Health"))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].consultationFee").value(1100.00))
                .andExpect(jsonPath("$.content[0].yearsExperience").value(11))
                .andExpect(jsonPath("$.content[0].prices[0].procedure").value("Registration"));

        // A price left out is withdrawn; an insurer left out no longer finds the doctor.
        save("""
                {"consultationFee":1100,"bio":null,"yearsExperience":11,"insurers":[],
                 "prices":[{"procedure":"ECG","priceInr":350,"everyVisit":false}]}
                """).andExpect(status().isOk());
        mvc.perform(get("/api/v1/doctors").param("insurance", "Star Health")).andExpect(jsonPath("$.content", hasSize(0)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM doctor_procedure_prices WHERE doctor_id = ?",
                Integer.class, rao.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown insurer, a procedure listed twice, or a negative fee is refused, and nothing changes")
    void refusals() throws Exception {
        save(body("[\"Made Up Insurance\"]", "[]", 900)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNKNOWN_INSURER"));
        save(body("[]", "[{\"procedure\":\"ECG\",\"priceInr\":300},{\"procedure\":\" ecg\",\"priceInr\":200}]", 900))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DUPLICATE_PROCEDURE"));
        save(body("[]", "[]", -5)).andExpect(status().isBadRequest());
        practice().andExpect(jsonPath("$.consultationFee").value(900.00));
    }

    @Test
    @DisplayName("only a doctor, and only their own")
    void doctorsOnly() throws Exception {
        User patient = User.builder().passwordHash("{noop}x").fullName("Meera").role(Role.PATIENT).enabled(true).build();
        patient.setEmail("meera-" + UUID.randomUUID() + "@practice.test");
        patient = userRepository.save(patient);
        mvc.perform(get("/api/v1/doctors/me/practice").header("Authorization", "Bearer " + jwtService.issueAccessToken(patient)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/doctors/me/practice")).andExpect(status().isUnauthorized());
    }

    private static String body(String insurers, String prices, int fee) {
        return """
                {"consultationFee":%d,"bio":"","yearsExperience":10,"insurers":%s,"prices":%s}
                """.formatted(fee, insurers, prices);
    }

    private ResultActions practice() throws Exception {
        return mvc.perform(get("/api/v1/doctors/me/practice").header("Authorization", bearer()));
    }

    private ResultActions save(String json) throws Exception {
        return mvc.perform(put("/api/v1/doctors/me/practice").header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String bearer() {
        return "Bearer " + jwtService.issueAccessToken(raoUser);
    }
}
