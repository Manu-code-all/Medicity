package com.medicity.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The body guide's answers travel with the booking to the doctor, and survive a move. */
@AutoConfigureMockMvc
@DisplayName("Pre-consultation intake")
class IntakeHandoffTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository userRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired PrescriptionRepository prescriptionRepository;
    @Autowired JwtService jwtService;
    @Autowired Clock clock;

    private User meeraUser;
    private User raoUser;
    private Doctor rao;

    private static final String INTAKE = """
            {"area":"Chest","symptoms":["Heart racing or skipping beats"],"since":"A few days","suggested":"Cardiology"}""";

    @BeforeEach
    void setUp() {
        prescriptionRepository.deleteAllInBatch();
        appointmentRepository.deleteAll();
        slotRepository.deleteAll();
        patientRepository.deleteAll();
        doctorRepository.deleteAll();
        userRepository.deleteAll();
        meeraUser = persistUser(Role.PATIENT);
        patientRepository.save(Patient.builder().user(meeraUser)
                .dateOfBirth(LocalDate.of(1993, 3, 21)).gender(Patient.Gender.FEMALE).build());
        raoUser = persistUser(Role.DOCTOR);
        rao = doctorRepository.save(Doctor.builder().user(raoUser).specialization("Cardiology")
                .licenseNumber("LIC-" + UUID.randomUUID().toString().substring(0, 8))
                .consultationFee(new BigDecimal("900.00")).yearsExperience(10).build());
    }

    @Test
    @DisplayName("the doctor sees what the patient told the body guide; a move keeps it")
    void travelsToTheDoctor() throws Exception {
        String booked = book(slot(2), """
                {"slotId":"%s","reason":"Racing heartbeat after coffee","intake":%s}""")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(booked).path("id").asText();

        mvc.perform(get("/api/v1/doctors/me/visits/" + id).header("Authorization", bearer(raoUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intake.area").value("Chest"))
                .andExpect(jsonPath("$.intake.symptoms[0]").value("Heart racing or skipping beats"))
                .andExpect(jsonPath("$.intake.since").value("A few days"))
                .andExpect(jsonPath("$.reason").value("Racing heartbeat after coffee"));

        String moved = mvc.perform(post("/api/v1/appointments/" + id + "/reschedule")
                        .header("Authorization", bearer(meeraUser)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slotId\":\"" + slot(3).getId() + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(appointmentRepository.findById(UUID.fromString(json.readTree(moved).path("id").asText()))
                .orElseThrow().getIntake().area()).isEqualTo("Chest");
    }

    @Test
    @DisplayName("without answers there is no intake; oversized answers are refused")
    void optionalAndBounded() throws Exception {
        String booked = book(slot(2), "{\"slotId\":\"%s\"}").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/v1/doctors/me/visits/" + json.readTree(booked).path("id").asText())
                        .header("Authorization", bearer(raoUser)))
                .andExpect(jsonPath("$.intake").doesNotExist());

        book(slot(3), """
                {"slotId":"%s","intake":{"area":"","symptoms":[]}}""")
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions book(AppointmentSlot slot, String template) throws Exception {
        return mvc.perform(post("/api/v1/appointments").header("Authorization", bearer(meeraUser))
                .contentType(MediaType.APPLICATION_JSON)
                // Extra arguments are ignored, so templates without an intake still format.
                .content(template.formatted(slot.getId(), INTAKE)));
    }

    private AppointmentSlot slot(int daysAhead) {
        Instant start = clock.instant().plus(daysAhead, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        return slotRepository.save(AppointmentSlot.builder().doctor(rao).startsAt(start)
                .endsAt(start.plus(30, ChronoUnit.MINUTES)).build());
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
