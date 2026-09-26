package com.medicity.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicity.doctor.Doctor;
import com.medicity.doctor.DoctorRepository;
import com.medicity.patient.Patient;
import com.medicity.patient.PatientRepository;
import com.medicity.scheduling.*;
import com.medicity.support.AbstractIntegrationTest;
import com.medicity.user.Role;
import com.medicity.user.User;
import com.medicity.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization tests driven through the real HTTP stack.
 *
 * <p>These deliberately exercise {@link MockMvc} rather than calling services
 * directly. Authorization in this application is assembled from several layers —
 * the filter chain, {@code @PreAuthorize}, and row-level ownership checks — and a
 * service-level test would bypass the first two entirely. A test that skips the
 * layers it is meant to verify proves nothing.
 *
 * <p>The central claim under test: <b>a patient cannot read another patient's
 * appointment, even holding a valid token and a correct id.</b> That is the
 * Insecure Direct Object Reference class of vulnerability, and role checks alone
 * do not prevent it — every patient carries {@code ROLE_PATIENT}.
 */
@AutoConfigureMockMvc
@DisplayName("Appointment access control")
class AppointmentAccessControlTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository userRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired BookingService bookingService;
    @Autowired JwtService jwtService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired Clock clock;
    @Value("${medicity.jwt.secret}") String jwtSecret;

    private String aliceToken;
    private String malloryToken;
    private String treatingDoctorToken;
    private String otherDoctorToken;
    private UUID aliceAppointmentId;

    @BeforeEach
    void setUp() {
        appointmentRepository.deleteAll();
        slotRepository.deleteAll();
        patientRepository.deleteAll();
        doctorRepository.deleteAll();
        userRepository.deleteAll();

        User aliceUser = persistUser("alice@medicity.test", "Alice Menon", Role.PATIENT);
        Patient alice = persistPatient(aliceUser);
        aliceToken = jwtService.issueAccessToken(aliceUser);

        User malloryUser = persistUser("mallory@medicity.test", "Mallory Das", Role.PATIENT);
        persistPatient(malloryUser);
        malloryToken = jwtService.issueAccessToken(malloryUser);

        User treatingUser = persistUser("dr.rao@medicity.test", "Dr. Anjali Rao", Role.DOCTOR);
        Doctor treating = persistDoctor(treatingUser, "Cardiology");
        treatingDoctorToken = jwtService.issueAccessToken(treatingUser);

        User otherUser = persistUser("dr.iyer@medicity.test", "Dr. Suresh Iyer", Role.DOCTOR);
        persistDoctor(otherUser, "Neurology");
        otherDoctorToken = jwtService.issueAccessToken(otherUser);

        Instant start = clock.instant().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        AppointmentSlot slot = slotRepository.save(AppointmentSlot.builder()
                .doctor(treating)
                .startsAt(start)
                .endsAt(start.plus(30, ChronoUnit.MINUTES))
                .status(SlotStatus.OPEN)
                .build());

        aliceAppointmentId = bookingService.book(slot.getId(), alice.getId(), "Chest pain").getId();
    }

    @Test
    @DisplayName("the owning patient can read her own appointment")
    void ownerCanRead() throws Exception {
        mvc.perform(get("/api/v1/appointments/" + aliceAppointmentId)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(aliceAppointmentId.toString()))
                .andExpect(jsonPath("$.reason").value("Chest pain"));
    }

    @Test
    @DisplayName("another patient is refused, even with a valid token and the correct id")
    void otherPatientIsRefused() throws Exception {
        mvc.perform(get("/api/v1/appointments/" + aliceAppointmentId)
                        .header("Authorization", "Bearer " + malloryToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                // The body must not leak the reason, the patient, or the time —
                // a 403 that describes the resource still discloses it.
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.patientId").doesNotExist());
    }

    @Test
    @DisplayName("the treating doctor can read it; an unrelated doctor cannot")
    void doctorAccessIsScopedToTheirOwnPatients() throws Exception {
        mvc.perform(get("/api/v1/appointments/" + aliceAppointmentId)
                        .header("Authorization", "Bearer " + treatingDoctorToken))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/appointments/" + aliceAppointmentId)
                        .header("Authorization", "Bearer " + otherDoctorToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("another patient cannot cancel someone else's appointment")
    void otherPatientCannotCancel() throws Exception {
        mvc.perform(post("/api/v1/appointments/" + aliceAppointmentId + "/cancel")
                        .header("Authorization", "Bearer " + malloryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("reason", "nuisance"))))
                .andExpect(status().isForbidden());

        // The attempt must have changed nothing.
        var stillBooked = appointmentRepository.findById(aliceAppointmentId).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(stillBooked.getStatus())
                .isEqualTo(AppointmentStatus.BOOKED);
    }

    @Test
    @DisplayName("an unauthenticated request is rejected")
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/v1/appointments/" + aliceAppointmentId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a refresh token from before the switch to opaque tokens is not an access token")
    void legacyRefreshTokenIsNotAnAccessToken() throws Exception {
        User alice = userRepository.findByEmail("alice@medicity.test").orElseThrow();
        // Refresh tokens used to be JWTs signed with the access-token key. Ones
        // issued before the switch stay correctly signed and unexpired for up
        // to 7 days, and must still be refused as bearer credentials.
        String refresh = Jwts.builder()
                .subject(alice.getId().toString())
                .claim("role", "PATIENT")
                .claim("typ", "refresh")
                .issuedAt(Date.from(clock.instant()))
                .expiration(Date.from(clock.instant().plus(1, ChronoUnit.DAYS)))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();

        mvc.perform(get("/api/v1/appointments/" + aliceAppointmentId)
                        .header("Authorization", "Bearer " + refresh))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a tampered token is rejected")
    void tamperedTokenIsRejected() throws Exception {
        // Flip the last character of the signature.
        char last = aliceToken.charAt(aliceToken.length() - 1);
        String forged = aliceToken.substring(0, aliceToken.length() - 1)
                + (last == 'A' ? 'B' : 'A');

        mvc.perform(get("/api/v1/appointments/" + aliceAppointmentId)
                        .header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    // --- fixtures -------------------------------------------------------

    private User persistUser(String email, String name, Role role) {
        User user = User.builder()
                .passwordHash(passwordEncoder.encode("correct-horse-battery-staple"))
                .fullName(name)
                .role(role)
                .enabled(true)
                .build();
        user.setEmail(email);
        return userRepository.save(user);
    }

    private Patient persistPatient(User user) {
        return patientRepository.save(Patient.builder()
                .user(user)
                .dateOfBirth(LocalDate.of(1993, 3, 21))
                .gender(Patient.Gender.UNDISCLOSED)
                .build());
    }

    private Doctor persistDoctor(User user, String specialization) {
        return doctorRepository.save(Doctor.builder()
                .user(user)
                .specialization(specialization)
                .licenseNumber("LIC-" + UUID.randomUUID().toString().substring(0, 8))
                .consultationFee(new BigDecimal("900.00"))
                .yearsExperience(10)
                .build());
    }
}
