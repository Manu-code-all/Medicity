package com.medicity.review;

import com.medicity.clinical.PrescriptionRepository;
import com.medicity.doctor.Doctor;
import com.medicity.doctor.DoctorRepository;
import com.medicity.patient.Patient;
import com.medicity.patient.PatientRepository;
import com.medicity.scheduling.Appointment;
import com.medicity.scheduling.AppointmentRepository;
import com.medicity.scheduling.AppointmentSlot;
import com.medicity.scheduling.AppointmentStatus;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Reviews: only from a visit that happened, only by its patient, only once. */
@AutoConfigureMockMvc
@DisplayName("Doctor reviews")
class ReviewTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired PrescriptionRepository prescriptionRepository;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private User meeraUser;
    private Patient meera;
    private User arjunUser;
    private Patient arjun;
    private Doctor rao;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM doctor_reviews");
        prescriptionRepository.deleteAllInBatch();
        appointmentRepository.deleteAll();
        slotRepository.deleteAll();
        patientRepository.deleteAll();
        doctorRepository.deleteAll();
        userRepository.deleteAll();

        meeraUser = persistUser("Meera Nair", Role.PATIENT);
        meera = persistPatient(meeraUser);
        arjunUser = persistUser("Arjun Mehta", Role.PATIENT);
        arjun = persistPatient(arjunUser);
        rao = doctorRepository.save(Doctor.builder()
                .user(persistUser("Dr. Anjali Rao", Role.DOCTOR))
                .specialization("Cardiology")
                .licenseNumber("LIC-" + UUID.randomUUID().toString().substring(0, 8))
                .consultationFee(new BigDecimal("900.00"))
                .yearsExperience(10)
                .build());
    }

    @Test
    @DisplayName("a completed visit can be reviewed once; the directory and the doctor's page show it")
    void reviewOnce() throws Exception {
        Appointment seen = visit(meera, -3, AppointmentStatus.COMPLETED);
        Appointment alsoSeen = visit(arjun, -2, AppointmentStatus.COMPLETED);

        review(meeraUser, seen, 5, "Explained everything").andExpect(status().isNoContent());
        review(meeraUser, seen, 1, "Changed my mind")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REVIEWED"));
        review(arjunUser, alsoSeen, 4, null).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/doctors").param("q", "Rao"))
                .andExpect(jsonPath("$.content[0].rating").value(4.5))
                .andExpect(jsonPath("$.content[0].reviewCount").value(2));
        mvc.perform(get("/api/v1/doctors/" + rao.getId() + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.reviewer == 'Meera N.')].comment").value("Explained everything"));
    }

    @Test
    @DisplayName("not before the visit happened, not someone else's visit, and only 1 to 5 stars")
    void onlyRealVisitsByTheirPatient() throws Exception {
        Appointment upcoming = visit(meera, 2, AppointmentStatus.BOOKED);
        Appointment missed = visit(meera, -2, AppointmentStatus.NO_SHOW);
        Appointment seen = visit(meera, -3, AppointmentStatus.COMPLETED);

        review(meeraUser, upcoming, 5, null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NOT_REVIEWABLE"));
        review(meeraUser, missed, 1, null).andExpect(status().isUnprocessableEntity());
        review(arjunUser, seen, 1, "Never met her").andExpect(status().isForbidden());
        review(meeraUser, seen, 6, null).andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM doctor_reviews", Integer.class)).isZero();
        mvc.perform(get("/api/v1/doctors").param("q", "Rao"))
                .andExpect(jsonPath("$.content[0].rating").doesNotExist())
                .andExpect(jsonPath("$.content[0].reviewCount").value(0));
    }

    @Test
    @DisplayName("the patient's history marks which visits are already reviewed")
    void historyMarksReviewed() throws Exception {
        Appointment reviewed = visit(meera, -3, AppointmentStatus.COMPLETED);
        Appointment notYet = visit(meera, -5, AppointmentStatus.COMPLETED);
        review(meeraUser, reviewed, 4, null).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/patients/me/appointments").param("scope", "past")
                        .header("Authorization", "Bearer " + jwtService.issueAccessToken(meeraUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '%s')].reviewed".formatted(reviewed.getId())).value(true))
                .andExpect(jsonPath("$.content[?(@.id == '%s')].reviewed".formatted(notYet.getId())).value(false));
    }

    private ResultActions review(User user, Appointment visit, int rating, String comment) throws Exception {
        String body = comment == null
                ? "{\"rating\":%d}".formatted(rating)
                : "{\"rating\":%d,\"comment\":\"%s\"}".formatted(rating, comment);
        return mvc.perform(post("/api/v1/appointments/" + visit.getId() + "/review")
                .header("Authorization", "Bearer " + jwtService.issueAccessToken(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private Appointment visit(Patient patient, int daysFromNow, AppointmentStatus status) {
        Instant start = clock.instant().plus(daysFromNow, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        AppointmentSlot slot = slotRepository.save(AppointmentSlot.builder()
                .doctor(rao).startsAt(start).endsAt(start.plus(30, ChronoUnit.MINUTES)).build());
        return appointmentRepository.save(Appointment.builder()
                .slot(slot).patient(patient).status(status).scheduledAt(start).build());
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

    private Patient persistPatient(User user) {
        return patientRepository.save(Patient.builder()
                .user(user)
                .dateOfBirth(LocalDate.of(1993, 3, 21))
                .gender(Patient.Gender.UNDISCLOSED)
                .build());
    }
}
