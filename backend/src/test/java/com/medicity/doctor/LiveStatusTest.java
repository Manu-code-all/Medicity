package com.medicity.doctor;

import com.medicity.clinical.PrescriptionRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Is my doctor running late?", estimated from visits still open after their slot ended. */
@AutoConfigureMockMvc
@DisplayName("Doctor's live status")
class LiveStatusTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired PrescriptionRepository prescriptionRepository;
    @Autowired JwtService jwtService;
    @Autowired Clock clock;

    private User meeraUser;
    private Patient meera;
    private Doctor rao;

    @BeforeEach
    void setUp() {
        prescriptionRepository.deleteAllInBatch();
        appointmentRepository.deleteAll();
        slotRepository.deleteAll();
        patientRepository.deleteAll();
        doctorRepository.deleteAll();
        userRepository.deleteAll();
        meeraUser = persistUser(Role.PATIENT);
        meera = patientRepository.save(Patient.builder().user(meeraUser)
                .dateOfBirth(LocalDate.of(1993, 3, 21)).gender(Patient.Gender.FEMALE).build());
        rao = doctorRepository.save(Doctor.builder().user(persistUser(Role.DOCTOR)).specialization("Cardiology")
                .licenseNumber("LIC-" + UUID.randomUUID().toString().substring(0, 8))
                .consultationFee(new BigDecimal("900.00")).yearsExperience(10).build());
    }

    @Test
    @DisplayName("no open visits: on time")
    void onTime() throws Exception {
        visit(-60, AppointmentStatus.COMPLETED);           // overdue, but closed: the doctor moved on
        status().andExpect(jsonPath("$.state").value("ON_TIME"))
                .andExpect(jsonPath("$.delayMinutes").value(0))
                .andExpect(jsonPath("$.visitInProgress").value(false));
    }

    @Test
    @DisplayName("a visit still open 30 minutes after its slot ended: running about 30 minutes late")
    void runningLate() throws Exception {
        visit(-60, AppointmentStatus.BOOKED);               // 60 to 30 minutes ago, still open
        status().andExpect(jsonPath("$.state").value("RUNNING_LATE"))
                .andExpect(jsonPath("$.delayMinutes", allOf(greaterThanOrEqualTo(29), lessThanOrEqualTo(31))));
    }

    @Test
    @DisplayName("a visit inside its own slot is in progress, not late; one forgotten hours ago is ignored")
    void inProgressAndForgotten() throws Exception {
        visit(-10, AppointmentStatus.BOOKED);               // started 10 minutes ago, ends in 20
        visit(-240, AppointmentStatus.BOOKED);              // ended three and a half hours ago, never closed
        status().andExpect(jsonPath("$.state").value("ON_TIME"))
                .andExpect(jsonPath("$.delayMinutes").value(0))
                .andExpect(jsonPath("$.visitInProgress").value(true));
    }

    @Test
    @DisplayName("for signed-in users only")
    void signedIn() throws Exception {
        mvc.perform(get("/api/v1/doctors/" + rao.getId() + "/live-status"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    private ResultActions status() throws Exception {
        return mvc.perform(get("/api/v1/doctors/" + rao.getId() + "/live-status")
                        .header("Authorization", "Bearer " + jwtService.issueAccessToken(meeraUser)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    /** A 30-minute visit that started this many minutes from now. */
    private Appointment visit(int minutes, AppointmentStatus status) {
        Instant start = clock.instant().plus(minutes, ChronoUnit.MINUTES);
        AppointmentSlot slot = slotRepository.save(AppointmentSlot.builder().doctor(rao).startsAt(start)
                .endsAt(start.plus(30, ChronoUnit.MINUTES)).build());
        return appointmentRepository.save(Appointment.builder().slot(slot).patient(meera).status(status)
                .scheduledAt(start).build());
    }

    private User persistUser(Role role) {
        User user = User.builder().passwordHash("{noop}x").fullName("Test " + role.name().toLowerCase())
                .role(role).enabled(true).build();
        user.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@medicity.test");
        return userRepository.save(user);
    }
}
