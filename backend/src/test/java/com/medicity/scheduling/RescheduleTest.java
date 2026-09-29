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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Moving a visit to another time: both halves happen, or neither does.
 */
@AutoConfigureMockMvc
@DisplayName("Rescheduling a visit")
class RescheduleTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository userRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired PrescriptionRepository prescriptionRepository;
    @Autowired BookingService bookingService;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private User meeraUser;
    private Patient meera;
    private User arjunUser;
    private Patient arjun;
    private Doctor rao;
    private Doctor iyer;

    @BeforeEach
    void setUp() {
        prescriptionRepository.deleteAllInBatch();
        appointmentRepository.deleteAll();
        slotRepository.deleteAll();
        patientRepository.deleteAll();
        doctorRepository.deleteAll();
        userRepository.deleteAll();

        meeraUser = persistUser(Role.PATIENT);
        meera = persistPatient(meeraUser);
        arjunUser = persistUser(Role.PATIENT);
        arjun = persistPatient(arjunUser);
        rao = persistDoctor();
        iyer = persistDoctor();
    }

    @Test
    @DisplayName("moves the visit: the old time is released, the new one booked, and the new row remembers the old")
    void movesTheVisit() throws Exception {
        AppointmentSlot monday = slot(rao, 2);
        AppointmentSlot tuesday = slot(rao, 3);
        Appointment original = bookingService.book(monday.getId(), meera.getId(), "Chest pain on stairs");

        String body = reschedule(meeraUser, original.getId(), tuesday)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotId").value(tuesday.getId().toString()))
                .andExpect(jsonPath("$.status").value("BOOKED"))
                .andExpect(jsonPath("$.reason").value("Chest pain on stairs"))
                .andExpect(jsonPath("$.rescheduledFrom").value(original.getId().toString()))
                .andReturn().getResponse().getContentAsString();

        Appointment old = appointmentRepository.findById(original.getId()).orElseThrow();
        assertThat(old.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(old.getCancelReason()).isEqualTo("Moved to another time");
        // Monday is free again: someone else can have it.
        assertThat(bookingService.book(monday.getId(), arjun.getId(), null).getStatus()).isEqualTo(AppointmentStatus.BOOKED);

        String movedId = json.readTree(body).path("id").asText();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'APPOINTMENT_RESCHEDULED'"
                + " AND aggregate_id = ?::uuid", Integer.class, movedId)).isEqualTo(1);
    }

    @Test
    @DisplayName("if the new time was just taken, nothing changes: the original visit stands")
    void lostRaceLeavesTheOriginal() throws Exception {
        AppointmentSlot monday = slot(rao, 2);
        AppointmentSlot tuesday = slot(rao, 3);
        Appointment original = bookingService.book(monday.getId(), meera.getId(), null);
        bookingService.book(tuesday.getId(), arjun.getId(), null);

        reschedule(meeraUser, original.getId(), tuesday)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_ALREADY_BOOKED"));

        Appointment still = appointmentRepository.findById(original.getId()).orElseThrow();
        assertThat(still.getStatus()).isEqualTo(AppointmentStatus.BOOKED);
        assertThat(still.getCancelledAt()).isNull();
        assertThat(appointmentRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("a retry of a move that succeeded returns the same new visit")
    void retryReturnsTheSameMove() throws Exception {
        AppointmentSlot monday = slot(rao, 2);
        AppointmentSlot tuesday = slot(rao, 3);
        Appointment original = bookingService.book(monday.getId(), meera.getId(), null);

        String first = reschedule(meeraUser, original.getId(), tuesday).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = reschedule(meeraUser, original.getId(), tuesday).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(second).path("id")).isEqualTo(json.readTree(first).path("id"));
        assertThat(appointmentRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("only to another time with the same doctor, and only by whoever owns the visit")
    void sameDoctorAndOwnerOnly() throws Exception {
        AppointmentSlot monday = slot(rao, 2);
        Appointment original = bookingService.book(monday.getId(), meera.getId(), null);

        reschedule(meeraUser, original.getId(), slot(iyer, 3))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DIFFERENT_DOCTOR"));
        reschedule(arjunUser, original.getId(), slot(rao, 3))
                .andExpect(status().isForbidden());

        assertThat(appointmentRepository.findById(original.getId()).orElseThrow().getStatus())
                .isEqualTo(AppointmentStatus.BOOKED);
    }

    @Test
    @DisplayName("a cancelled visit cannot be moved")
    void cancelledCannotMove() throws Exception {
        Appointment original = bookingService.book(slot(rao, 2).getId(), meera.getId(), null);
        bookingService.cancel(original.getId(), "Feeling better");

        reschedule(meeraUser, original.getId(), slot(rao, 3))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NOT_RESCHEDULABLE"));
    }

    private ResultActions reschedule(User user, UUID appointmentId, AppointmentSlot to) throws Exception {
        return mvc.perform(post("/api/v1/appointments/" + appointmentId + "/reschedule")
                .header("Authorization", "Bearer " + jwtService.issueAccessToken(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slotId\":\"" + to.getId() + "\"}"));
    }

    private AppointmentSlot slot(Doctor doctor, int daysAhead) {
        Instant start = clock.instant().plus(daysAhead, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        return slotRepository.save(AppointmentSlot.builder()
                .doctor(doctor)
                .startsAt(start)
                .endsAt(start.plus(30, ChronoUnit.MINUTES))
                .status(SlotStatus.OPEN)
                .build());
    }

    private Doctor persistDoctor() {
        return doctorRepository.save(Doctor.builder()
                .user(persistUser(Role.DOCTOR))
                .specialization("Cardiology")
                .licenseNumber("LIC-" + UUID.randomUUID().toString().substring(0, 8))
                .consultationFee(new BigDecimal("900.00"))
                .yearsExperience(10)
                .build());
    }

    private User persistUser(Role role) {
        User user = User.builder()
                .passwordHash("{noop}irrelevant")
                .fullName("Test " + role.name().toLowerCase())
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
