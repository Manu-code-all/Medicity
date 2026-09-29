package com.medicity.video;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.medicity.scheduling.VisitType;
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

/** Who may join a video visit, when, and that a ticket works once. */
@AutoConfigureMockMvc
@DisplayName("Video visits: joining")
class VideoVisitTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository userRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired PrescriptionRepository prescriptionRepository;
    @Autowired JwtService jwtService;
    @Autowired VideoTickets tickets;
    @Autowired Clock clock;

    private User meeraUser;
    private Patient meera;
    private User raoUser;
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
        raoUser = persistUser(Role.DOCTOR);
        rao = doctorRepository.save(Doctor.builder()
                .user(raoUser)
                .specialization("Cardiology")
                .licenseNumber("LIC-" + UUID.randomUUID().toString().substring(0, 8))
                .consultationFee(new BigDecimal("900.00"))
                .yearsExperience(10)
                .build());
    }

    @Test
    @DisplayName("the patient and the doctor each get a ticket for their own side; it works once")
    void bothSidesGetATicket() throws Exception {
        Appointment visit = visit(10, VisitType.VIDEO, AppointmentStatus.BOOKED);

        String body = ticket(meeraUser, visit).andExpect(status().isOk())
                .andExpect(jsonPath("$.side").value("PATIENT"))
                .andExpect(jsonPath("$.iceServers[0]").value("stun:stun.l.google.com:19302"))
                .andReturn().getResponse().getContentAsString();
        ticket(raoUser, visit).andExpect(status().isOk()).andExpect(jsonPath("$.side").value("DOCTOR"));

        String t = json.readTree(body).path("ticket").asText();
        assertThat(t).hasSizeGreaterThanOrEqualTo(40);
        assertThat(tickets.redeem(t)).get().extracting(VideoTickets.Pending::appointmentId).isEqualTo(visit.getId());
        assertThat(tickets.redeem(t)).isEmpty();
        assertThat(tickets.redeem("made-up")).isEmpty();
    }

    @Test
    @DisplayName("nobody else, not an in-person or cancelled visit, and not days early")
    void refusals() throws Exception {
        Appointment video = visit(10, VisitType.VIDEO, AppointmentStatus.BOOKED);
        User stranger = persistUser(Role.PATIENT);
        patientRepository.save(Patient.builder().user(stranger)
                .dateOfBirth(LocalDate.of(1990, 1, 1)).gender(Patient.Gender.MALE).build());
        ticket(stranger, video).andExpect(status().isForbidden());

        ticket(meeraUser, visit(60, VisitType.IN_PERSON, AppointmentStatus.BOOKED))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NOT_A_VIDEO_VISIT"));
        ticket(meeraUser, visit(120, VisitType.VIDEO, AppointmentStatus.CANCELLED))
                .andExpect(jsonPath("$.code").value("VIDEO_NOT_OPEN"));
        ticket(meeraUser, visit(2 * 24 * 60, VisitType.VIDEO, AppointmentStatus.BOOKED))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VIDEO_NOT_OPEN"));
    }

    @Test
    @DisplayName("booking can choose video, and the visit says so")
    void bookingAsVideo() throws Exception {
        Instant start = clock.instant().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        AppointmentSlot slot = slotRepository.save(AppointmentSlot.builder()
                .doctor(rao).startsAt(start).endsAt(start.plus(30, ChronoUnit.MINUTES)).build());

        mvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + jwtService.issueAccessToken(meeraUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slotId\":\"%s\",\"visitType\":\"VIDEO\"}".formatted(slot.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.visitType").value("VIDEO"));
    }

    private ResultActions ticket(User user, Appointment visit) throws Exception {
        return mvc.perform(post("/api/v1/appointments/" + visit.getId() + "/video-ticket")
                .header("Authorization", "Bearer " + jwtService.issueAccessToken(user)));
    }

    /** A visit starting this many minutes from now. */
    private Appointment visit(int minutesAhead, VisitType type, AppointmentStatus status) {
        Instant start = clock.instant().plus(minutesAhead, ChronoUnit.MINUTES);
        AppointmentSlot slot = slotRepository.save(AppointmentSlot.builder()
                .doctor(rao).startsAt(start).endsAt(start.plus(30, ChronoUnit.MINUTES)).build());
        Appointment.AppointmentBuilder a = Appointment.builder()
                .slot(slot).patient(meera).status(status).scheduledAt(start).visitType(type);
        if (status == AppointmentStatus.CANCELLED) {
            a.cancelledAt(clock.instant()).cancelReason("Changed plans");
        }
        return appointmentRepository.save(a.build());
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
}
