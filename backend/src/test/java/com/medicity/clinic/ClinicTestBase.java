package com.medicity.clinic;

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
import com.medicity.security.JwtService;
import com.medicity.support.AbstractIntegrationTest;
import com.medicity.user.Role;
import com.medicity.user.User;
import com.medicity.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Two clinics, each with a doctor, a receptionist and an administrator, and
 * helpers to make the rest. The suites built on it share one question: who can
 * see and change what.
 */
@AutoConfigureMockMvc
@Import(ClinicTestBase.OfficeHours.class)
abstract class ClinicTestBase extends AbstractIntegrationTest {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /**
     * The application's clock, moved so that it reads 10 am India time today and
     * then runs on. Walk-in tokens are only given between 7 am and 9 pm, so tests
     * that add them would otherwise fail whenever CI happens to run at night.
     */
    @TestConfiguration
    static class OfficeHours {
        @Bean
        @Primary
        Clock officeClock() {
            Instant real = Instant.now();
            ZonedDateTime tenAm = real.atZone(INDIA).withHour(10).withMinute(0).withSecond(0).withNano(0);
            return Clock.offset(Clock.systemUTC(), Duration.between(real, tenAm.toInstant()));
        }
    }

    @Autowired Clock clock;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired PrescriptionRepository prescriptionRepository;
    @Autowired ClinicRepository clinicRepository;
    @Autowired JwtService jwtService;
    @Autowired PasswordEncoder passwordEncoder;

    Clinic clinicA;
    Clinic clinicB;
    User adminA;
    User adminB;
    User deskA;
    User deskB;
    Doctor doctorA;
    Doctor doctorB;

    @BeforeEach
    void setUpClinics() {
        wipe();
        clinicA = clinic("Alder Clinic");
        clinicB = clinic("Birch Hospital");
        adminA = staff(clinicA, Role.CLINIC_ADMIN, "Asha Admin");
        adminB = staff(clinicB, Role.CLINIC_ADMIN, "Bala Admin");
        deskA = staff(clinicA, Role.RECEPTIONIST, "Anu Desk");
        deskB = staff(clinicB, Role.RECEPTIONIST, "Bina Desk");
        doctorA = doctor(clinicA, "Dr. Alder", "General Medicine");
        doctorB = doctor(clinicB, "Dr. Birch", "General Medicine");
    }

    @AfterEach
    void tearDownClinics() {
        wipe();
    }

    private void wipe() {
        jdbc.update("DELETE FROM queue_days");
        prescriptionRepository.deleteAllInBatch();
        appointmentRepository.deleteAll();
        slotRepository.deleteAll();
        patientRepository.deleteAll();
        doctorRepository.deleteAll();
        userRepository.deleteAll();
        jdbc.update("DELETE FROM clinics");
    }

    // --- fixtures ---------------------------------------------------------------

    Clinic clinic(String name) {
        return clinicRepository.saveAndFlush(Clinic.builder().name(name).address("1 Test Road").city("Bengaluru")
                .latitude(12.97).longitude(77.64).build());
    }

    private static String hash;

    User user(String name, Role role) {
        if (hash == null) {
            hash = passwordEncoder.encode("not-used-" + UUID.randomUUID());   // BCrypt cost 12: once, not per user
        }
        User u = User.builder().fullName(name).role(role).enabled(true).passwordHash(hash).build();
        u.setEmail(name.toLowerCase().replaceAll("[^a-z]+", ".") + "." + UUID.randomUUID().toString().substring(0, 6)
                + "@test.example");
        return userRepository.saveAndFlush(u);
    }

    User staff(Clinic clinic, Role role, String name) {
        User u = user(name, role);
        jdbc.update("INSERT INTO clinic_staff (clinic_id, user_id, staff_role) VALUES (?, ?, ?)",
                clinic.getId(), u.getId(), role.name());
        return u;
    }

    Doctor doctor(Clinic clinic, String name, String specialization) {
        return doctorRepository.saveAndFlush(Doctor.builder()
                .user(user(name, Role.DOCTOR))
                .clinic(clinic)
                .specialization(specialization)
                .licenseNumber("LIC-" + UUID.randomUUID().toString().substring(0, 8))
                .consultationFee(new BigDecimal("500.00"))
                .yearsExperience(5)
                .build());
    }

    Patient patient(String name) {
        return patientRepository.saveAndFlush(Patient.builder().user(user(name, Role.PATIENT))
                .dateOfBirth(LocalDate.of(1990, 1, 1)).gender(Patient.Gender.UNDISCLOSED).build());
    }

    /** A free slot starting {@code fromNow} from now, 30 minutes long. */
    AppointmentSlot slot(Doctor doctor, Duration fromNow) {
        Instant start = clock.instant().plus(fromNow).truncatedTo(ChronoUnit.MINUTES);
        return slotRepository.saveAndFlush(AppointmentSlot.builder().doctor(doctor)
                .startsAt(start).endsAt(start.plus(30, ChronoUnit.MINUTES)).build());
    }

    /** A booked visit made directly, for slots that begin in the past (the booking service refuses those). */
    Appointment visit(AppointmentSlot slot, Patient patient) {
        return appointmentRepository.saveAndFlush(Appointment.builder().slot(slot).patient(patient)
                .status(AppointmentStatus.BOOKED).reason("Check-up").scheduledAt(slot.getStartsAt()).build());
    }

    /** The clinic-calendar date a slot falls on, as the desk's {@code ?date=} wants it. */
    static LocalDate dayOf(AppointmentSlot slot) {
        return slot.getStartsAt().atZone(INDIA).toLocalDate();
    }

    // --- requests ----------------------------------------------------------------

    String bearer(User user) {
        return "Bearer " + jwtService.issueAccessToken(user);
    }

    ResultActions send(MockHttpServletRequestBuilder request, User as, Object body) throws Exception {
        MockHttpServletRequestBuilder withAuth = as == null ? request : request.header("Authorization", bearer(as));
        return mvc.perform(body == null ? withAuth
                : withAuth.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    ResultActions getAs(User as, String path) throws Exception {
        return send(get(path), as, null);
    }

    ResultActions postAs(User as, String path, Object body) throws Exception {
        return send(post(path), as, body == null ? Map.of() : body);
    }

    ResultActions putAs(User as, String path, Object body) throws Exception {
        return send(put(path), as, body);
    }

    long audit(String action, Object entityId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ? AND entity_id = ?",
                Long.class, action, entityId.toString());
        return n == null ? 0 : n;
    }

    long auditForClinic(String action, Clinic clinic) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ? AND detail ->> 'clinicId' = ?",
                Long.class, action, clinic.getId().toString());
        return n == null ? 0 : n;
    }
}
