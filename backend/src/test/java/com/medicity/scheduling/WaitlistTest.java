package com.medicity.scheduling;

import com.medicity.clinical.PrescriptionRepository;
import com.medicity.doctor.Doctor;
import com.medicity.doctor.DoctorRepository;
import com.medicity.patient.ActingPatient;
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
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A full day's waiting list: who is told when a time opens, and when they stop waiting. */
@AutoConfigureMockMvc
@DisplayName("Waiting list")
class WaitlistTest extends AbstractIntegrationTest {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired PrescriptionRepository prescriptionRepository;
    @Autowired BookingService bookingService;
    @Autowired JwtService jwtService;
    @Autowired Clock clock;

    private User meeraUser;
    private Patient meera;
    private Patient arjun;
    private Doctor rao;
    private LocalDate day;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM slot_waitlist");
        prescriptionRepository.deleteAllInBatch();
        appointmentRepository.deleteAll();
        slotRepository.deleteAll();
        patientRepository.deleteAll();
        doctorRepository.deleteAll();
        userRepository.deleteAll();

        meeraUser = persistUser(Role.PATIENT);
        meera = patient(meeraUser);
        arjun = patient(persistUser(Role.PATIENT));
        rao = doctorRepository.save(Doctor.builder().user(persistUser(Role.DOCTOR)).specialization("Cardiology")
                .licenseNumber("LIC-" + UUID.randomUUID().toString().substring(0, 8))
                .consultationFee(new BigDecimal("900.00")).yearsExperience(10).build());
        day = clock.instant().atZone(INDIA).toLocalDate().plusDays(3);
    }

    @Test
    @DisplayName("a cancellation that day tells everyone waiting, and only them")
    void cancellationTellsTheWaiting() throws Exception {
        join(meeraUser, day, null).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE"));
        Appointment arjunsVisit = bookingService.book(slot(day, 11).getId(), arjun.getId(), null);
        Appointment otherDay = bookingService.book(slot(day.plusDays(1), 11).getId(), arjun.getId(), null);

        bookingService.cancel(otherDay.getId(), "Not that day");
        assertThat(openedEvents()).isZero();

        bookingService.cancel(arjunsVisit.getId(), "Feeling better");
        assertThat(openedEvents()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT payload->>'patientUserId' FROM outbox_events WHERE event_type = 'WAITLIST_SLOT_OPENED'"
                        + " AND aggregate_id IN (SELECT id FROM slot_waitlist)",
                String.class)).isEqualTo(meeraUser.getId().toString());
        mvc.perform(get("/api/v1/patients/me/waitlist").header("Authorization", bearer(meeraUser)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("NOTIFIED"));
    }

    @Test
    @DisplayName("moving a visit away from the day releases its time too")
    void moveReleases() throws Exception {
        join(meeraUser, day, null).andExpect(status().isCreated());
        Appointment visit = bookingService.book(slot(day, 11).getId(), arjun.getId(), null);

        bookingService.reschedule(visit.getId(), slot(day.plusDays(2), 11).getId());

        assertThat(openedEvents()).isEqualTo(1);
    }

    @Test
    @DisplayName("booking that doctor that day ends the wait; leaving means no alert")
    void bookingEndsItLeavingSilencesIt() throws Exception {
        join(meeraUser, day, null).andExpect(status().isCreated());
        bookingService.book(slot(day, 15).getId(), meera.getId(), null);
        mvc.perform(get("/api/v1/patients/me/waitlist").header("Authorization", bearer(meeraUser)))
                .andExpect(jsonPath("$", hasSize(0)));

        LocalDate later = day.plusDays(1);
        join(meeraUser, later, null).andExpect(status().isCreated());
        mvc.perform(delete("/api/v1/doctors/" + rao.getId() + "/waitlist").param("date", later.toString())
                        .header("Authorization", bearer(meeraUser)))
                .andExpect(status().isNoContent());
        Appointment visit = bookingService.book(slot(later, 11).getId(), arjun.getId(), null);
        bookingService.cancel(visit.getId(), null);
        assertThat(openedEvents()).isZero();
    }

    @Test
    @DisplayName("a family member's place is named for them; past days are refused")
    void familyAndDates() throws Exception {
        Patient lalitha = patientRepository.save(Patient.builder().guardianUserId(meeraUser.getId())
                .fullName("Lalitha Nair").relationship(Patient.Relationship.PARENT)
                .dateOfBirth(LocalDate.of(1961, 4, 10)).gender(Patient.Gender.FEMALE).build());
        join(meeraUser, day, lalitha.getId()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.patientName").value("Lalitha Nair"));
        Appointment visit = bookingService.book(slot(day, 11).getId(), arjun.getId(), null);
        bookingService.cancel(visit.getId(), null);
        assertThat(jdbc.queryForObject(
                "SELECT payload->>'forName' FROM outbox_events WHERE event_type = 'WAITLIST_SLOT_OPENED'"
                        + " AND aggregate_id IN (SELECT id FROM slot_waitlist)",
                String.class)).isEqualTo("Lalitha");

        join(meeraUser, clock.instant().atZone(INDIA).toLocalDate().minusDays(1), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WAITLIST_DATE"));
    }

    private int openedEvents() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'WAITLIST_SLOT_OPENED'"
                + " AND aggregate_id IN (SELECT id FROM slot_waitlist)", Integer.class);
    }

    private ResultActions join(User account, LocalDate date, UUID forPatient) throws Exception {
        var request = post("/api/v1/doctors/" + rao.getId() + "/waitlist")
                .header("Authorization", bearer(account))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"" + date + "\"}");
        if (forPatient != null) {
            request.header(ActingPatient.HEADER, forPatient.toString());
        }
        return mvc.perform(request);
    }

    /** A slot on that India-time day at that hour. */
    private AppointmentSlot slot(LocalDate date, int hour) {
        Instant start = date.atTime(LocalTime.of(hour, 0)).atZone(INDIA).toInstant();
        return slotRepository.save(AppointmentSlot.builder().doctor(rao).startsAt(start)
                .endsAt(start.plus(30, ChronoUnit.MINUTES)).build());
    }

    private Patient patient(User user) {
        return patientRepository.save(Patient.builder().user(user)
                .dateOfBirth(LocalDate.of(1990, 1, 1)).gender(Patient.Gender.UNDISCLOSED).build());
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
