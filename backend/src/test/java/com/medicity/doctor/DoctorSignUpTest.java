package com.medicity.doctor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicity.outbox.OutboxRelay;
import com.medicity.security.JwtService;
import com.medicity.support.AbstractIntegrationTest;
import com.medicity.user.Role;
import com.medicity.user.User;
import com.medicity.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Doctors signing up: unlisted and unbookable until an administrator checks
 * the registration number; weekly hours become bookable slots.
 */
@AutoConfigureMockMvc
@DisplayName("Doctor sign-up and hours")
class DoctorSignUpTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String WEEKDAYS_10_TO_12 = """
            {"days":[
              {"weekday":1,"startsAt":"10:00","endsAt":"12:00","slotMinutes":30},
              {"weekday":2,"startsAt":"10:00","endsAt":"12:00","slotMinutes":30},
              {"weekday":3,"startsAt":"10:00","endsAt":"12:00","slotMinutes":30},
              {"weekday":4,"startsAt":"10:00","endsAt":"12:00","slotMinutes":30},
              {"weekday":5,"startsAt":"10:00","endsAt":"12:00","slotMinutes":30}]}
            """;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @Autowired OutboxRelay relay;
    @Autowired DoctorHoursService hoursService;

    private User admin;

    @BeforeEach
    void setUp() {
        cleanUp();
        admin = users.save(withEmail(User.builder().passwordHash("{noop}x").fullName("Ops Admin")
                .role(Role.ADMIN).enabled(true).build(), "admin-" + UUID.randomUUID() + "@doctor.test"));
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    @DisplayName("a new doctor can sign in and set hours at once, but is neither listed nor bookable until verified")
    void unverifiedIsInvisible() throws Exception {
        String token = register("Dr. Nisha Rao", "KMC-99001");

        me(token, "/profile").andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.registrationNumber").value("KMC-99001"))
                .andExpect(jsonPath("$.medicalCouncil").value("Karnataka Medical Council"));
        saveHours(token, WEEKDAYS_10_TO_12).andExpect(status().isOk());

        mvc.perform(get("/api/v1/doctors").param("q", "Nisha")).andExpect(jsonPath("$.content", hasSize(0)));
        mvc.perform(get("/api/v1/doctors/suggest").param("q", "Nisha")).andExpect(jsonPath("$.doctors", hasSize(0)));

        UUID doctorId = doctorId("KMC-99001");
        mvc.perform(get("/api/v1/doctors/" + doctorId + "/slots")
                        .param("from", "2020-01-01T00:00:00Z").param("to", "2099-01-01T00:00:00Z"))
                .andExpect(jsonPath("$", hasSize(0)));

        // A slot id obtained some other way is refused too.
        UUID slot = anySlot(doctorId);
        book(patientToken(), slot).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SLOT_NOT_OPEN"));
    }

    @Test
    @DisplayName("the administrator checks the registration; the doctor becomes bookable and is told, admins were told first")
    void verification() throws Exception {
        register("Dr. Nisha Rao", "KMC-99002");
        UUID doctorId = doctorId("KMC-99002");

        mvc.perform(get("/api/v1/admin/doctors/pending").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].registrationNumber").value("KMC-99002"));
        mvc.perform(post("/api/v1/admin/doctors/" + doctorId + "/verify").header("Authorization", "Bearer " + patientToken()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/doctors/" + doctorId + "/verify").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true));
        mvc.perform(post("/api/v1/admin/doctors/" + doctorId + "/verify").header("Authorization", bearer(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_VERIFIED"));

        mvc.perform(get("/api/v1/doctors").param("q", "Nisha")).andExpect(jsonPath("$.content", hasSize(1)));

        relay.drain();
        assertThat(notifications(admin.getId(), "Doctor awaiting verification")).isEqualTo(1);
        User doctor = users.findByEmail("kmc-99002@doctor.test").orElseThrow();
        assertThat(notifications(doctor.getId(), "You are verified")).isEqualTo(1);
    }

    @Test
    @DisplayName("weekday hours open exactly the slots they describe, four weeks ahead, in India time")
    void hoursOpenSlots() throws Exception {
        String token = register("Dr. Nisha Rao", "KMC-99003");
        UUID doctorId = doctorId("KMC-99003");

        saveHours(token, WEEKDAYS_10_TO_12).andExpect(status().isOk())
                .andExpect(jsonPath("$.hours", hasSize(5)))
                .andExpect(jsonPath("$.slotsOpened").value(80));   // 20 weekdays x 4 half-hours

        assertThat(jdbc.queryForList("""
                SELECT DISTINCT to_char(starts_at AT TIME ZONE 'Asia/Kolkata', 'HH24:MI') FROM appointment_slots
                WHERE doctor_id = ? ORDER BY 1
                """, String.class, doctorId)).containsExactly("10:00", "10:30", "11:00", "11:30");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM appointment_slots WHERE doctor_id = ?
                  AND extract(isodow FROM starts_at AT TIME ZONE 'Asia/Kolkata') > 5
                """, Integer.class, doctorId)).isZero();

        // The nightly top-up finds nothing missing.
        assertThat(hoursService.rollForward()).isZero();
        me(token, "/hours").andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[0].startsAt").value("10:00:00"));
    }

    @Test
    @DisplayName("a morning and an afternoon session open slots in both and none in the lunch break")
    void twoSessionsADay() throws Exception {
        String token = register("Dr. Nisha Rao", "KMC-99007");
        UUID doctorId = doctorId("KMC-99007");

        saveHours(token, """
                {"days":[{"weekday":1,"startsAt":"14:00","endsAt":"15:00","slotMinutes":30},
                         {"weekday":1,"startsAt":"09:00","endsAt":"11:00","slotMinutes":30}]}
                """).andExpect(status().isOk())
                .andExpect(jsonPath("$.slotsOpened").value(24))            // 4 Mondays x (4 + 2)
                .andExpect(jsonPath("$.hours[0].startsAt").value("09:00:00"))
                .andExpect(jsonPath("$.hours[1].startsAt").value("14:00:00"));

        assertThat(jdbc.queryForList("""
                SELECT DISTINCT to_char(starts_at AT TIME ZONE 'Asia/Kolkata', 'HH24:MI') FROM appointment_slots
                WHERE doctor_id = ? ORDER BY 1
                """, String.class, doctorId)).containsExactly("09:00", "09:30", "10:00", "10:30", "14:00", "14:30");
    }

    @Test
    @DisplayName("a day off removes that day's open slots, keeps and counts the booked visit, and reopens when removed")
    void leave() throws Exception {
        String token = register("Dr. Nisha Rao", "KMC-99008");
        UUID doctorId = doctorId("KMC-99008");
        saveHours(token, WEEKDAYS_10_TO_12);
        mvc.perform(post("/api/v1/admin/doctors/" + doctorId + "/verify").header("Authorization", bearer(admin)));
        UUID booked = anySlot(doctorId);
        book(patientToken(), booked).andExpect(status().isCreated());
        LocalDate day = jdbc.queryForObject(
                "SELECT (starts_at AT TIME ZONE 'Asia/Kolkata')::date FROM appointment_slots WHERE id = ?",
                LocalDate.class, booked);

        mvc.perform(post("/api/v1/doctors/me/leave").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"day\":\"%s\",\"note\":\"Conference\"}".formatted(day)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookedVisits").value(1))
                .andExpect(jsonPath("$.note").value("Conference"));
        assertThat(slotsOn(doctorId, day)).containsExactly(booked);

        // The nightly top-up leaves the day alone.
        hoursService.rollForward();
        assertThat(slotsOn(doctorId, day)).containsExactly(booked);
        me(token, "/leave").andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].day").value(day.toString()));

        mvc.perform(delete("/api/v1/doctors/me/leave/" + day).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.slotsOpened").value(3));
        assertThat(slotsOn(doctorId, day)).hasSize(4).contains(booked);

        mvc.perform(post("/api/v1/doctors/me/leave").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"day\":\"2020-01-01\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("LEAVE_IN_PAST"));
    }

    @Test
    @DisplayName("changing hours replaces unbooked future slots but keeps the booked one")
    void changingHoursKeepsBookings() throws Exception {
        String token = register("Dr. Nisha Rao", "KMC-99004");
        UUID doctorId = doctorId("KMC-99004");
        saveHours(token, WEEKDAYS_10_TO_12);
        mvc.perform(post("/api/v1/admin/doctors/" + doctorId + "/verify").header("Authorization", bearer(admin)));
        UUID booked = anySlot(doctorId);
        book(patientToken(), booked).andExpect(status().isCreated());

        saveHours(token, """
                {"days":[{"weekday":6,"startsAt":"09:00","endsAt":"10:00","slotMinutes":60}]}
                """).andExpect(status().isOk()).andExpect(jsonPath("$.slotsOpened").value(4));

        List<UUID> left = jdbc.queryForList("SELECT id FROM appointment_slots WHERE doctor_id = ?", UUID.class, doctorId);
        assertThat(left).hasSize(5).contains(booked);
    }

    @Test
    @DisplayName("bad hours and bad registrations are refused with a reason")
    void refusals() throws Exception {
        String token = register("Dr. Nisha Rao", "KMC-99005");

        saveHours(token, "{\"days\":[{\"weekday\":1,\"startsAt\":\"12:00\",\"endsAt\":\"10:00\",\"slotMinutes\":30}]}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_HOURS"));
        saveHours(token, "{\"days\":[{\"weekday\":1,\"startsAt\":\"10:00\",\"endsAt\":\"12:00\",\"slotMinutes\":25}]}")
                .andExpect(status().isBadRequest());
        saveHours(token, """
                {"days":[{"weekday":1,"startsAt":"10:00","endsAt":"12:00","slotMinutes":30},
                         {"weekday":1,"startsAt":"11:30","endsAt":"13:00","slotMinutes":30}]}
                """).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SESSIONS_OVERLAP"));
        saveHours(token, """
                {"days":[{"weekday":2,"startsAt":"08:00","endsAt":"09:00","slotMinutes":30},
                         {"weekday":2,"startsAt":"10:00","endsAt":"11:00","slotMinutes":30},
                         {"weekday":2,"startsAt":"12:00","endsAt":"13:00","slotMinutes":30},
                         {"weekday":2,"startsAt":"14:00","endsAt":"15:00","slotMinutes":30}]}
                """).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TOO_MANY_SESSIONS"));

        registration("Dr. Same Number", "kmc-99005@doctor.test".replace("kmc", "other"), "KMC-99005", "Cardiology")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REGISTRATION_TAKEN"));
        registration("Dr. Made Up", "madeup@doctor.test", "KMC-99006", "Astrology")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UNKNOWN_SPECIALITY"));
    }

    // --- helpers -----------------------------------------------------------

    private String register(String name, String registrationNumber) throws Exception {
        String body = registration(name, registrationNumber.toLowerCase() + "@doctor.test", registrationNumber, "Dermatology")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("accessToken").asText();
    }

    private ResultActions registration(String name, String email, String number, String speciality) throws Exception {
        return mvc.perform(post("/api/v1/auth/register/doctor").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"%s","fullName":"%s","phone":"%s","specialization":"%s",
                 "medicalCouncil":"Karnataka Medical Council","registrationNumber":"%s",
                 "qualification":"MBBS, MD (Dermatology)","yearsExperience":6,"consultationFee":800}
                """.formatted(email, PASSWORD, name, randomMobile(), speciality, number)));
    }

    private ResultActions saveHours(String token, String body) throws Exception {
        return mvc.perform(put("/api/v1/doctors/me/hours").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions me(String token, String path) throws Exception {
        return mvc.perform(get("/api/v1/doctors/me" + path).header("Authorization", "Bearer " + token));
    }

    private ResultActions book(String token, UUID slotId) throws Exception {
        return mvc.perform(post("/api/v1/appointments").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"slotId\":\"%s\",\"reason\":\"Rash\"}".formatted(slotId)));
    }

    private String patientToken() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"patient-%s@doctor.test","password":"%s","fullName":"Test Patient","dateOfBirth":"1990-01-01"}
                        """.formatted(UUID.randomUUID(), PASSWORD)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("accessToken").asText();
    }

    private UUID doctorId(String registrationNumber) {
        return jdbc.queryForObject("SELECT id FROM doctors WHERE license_number = ?", UUID.class, registrationNumber);
    }

    private List<UUID> slotsOn(UUID doctorId, LocalDate day) {
        return jdbc.queryForList("""
                SELECT id FROM appointment_slots
                WHERE doctor_id = ? AND (starts_at AT TIME ZONE 'Asia/Kolkata')::date = ?
                """, UUID.class, doctorId, day);
    }

    private UUID anySlot(UUID doctorId) {
        return jdbc.queryForObject(
                "SELECT id FROM appointment_slots WHERE doctor_id = ? ORDER BY starts_at LIMIT 1", UUID.class, doctorId);
    }

    private int notifications(UUID userId, String title) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND title = ?",
                Integer.class, userId, title);
    }

    private String bearer(User user) {
        return "Bearer " + jwt.issueAccessToken(user);
    }

    private static User withEmail(User user, String email) {
        user.setEmail(email);
        return user;
    }

    private static String randomMobile() {
        return "9" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
    }

    private void cleanUp() {
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("""
                DELETE FROM appointments WHERE slot_id IN (SELECT s.id FROM appointment_slots s
                  JOIN doctors d ON d.id = s.doctor_id JOIN users u ON u.id = d.user_id WHERE u.email LIKE '%@doctor.test')
                """);
        jdbc.update("DELETE FROM patients WHERE user_id IN (SELECT id FROM users WHERE email LIKE '%@doctor.test')");
        jdbc.update("DELETE FROM doctors WHERE user_id IN (SELECT id FROM users WHERE email LIKE '%@doctor.test')");
        jdbc.update("DELETE FROM refresh_tokens WHERE user_id IN (SELECT id FROM users WHERE email LIKE '%@doctor.test')");
        jdbc.update("DELETE FROM users WHERE email LIKE '%@doctor.test'");
    }
}
