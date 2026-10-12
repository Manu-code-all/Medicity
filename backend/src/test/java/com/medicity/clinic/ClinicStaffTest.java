package com.medicity.clinic;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicity.doctor.Doctor;
import com.medicity.patient.Patient;
import com.medicity.privacy.ConsentService;
import com.medicity.privacy.ConsentType;
import com.medicity.queue.QueueService;
import com.medicity.scheduling.Appointment;
import com.medicity.scheduling.AppointmentSlot;
import com.medicity.scheduling.AppointmentStatus;
import com.medicity.user.Role;
import com.medicity.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Running a clinic: the platform sets it up, its administrator keeps the
 * profile and adds people, accounts made with a temporary password must change
 * it, the directory shows and filters by clinic, and the report adds up.
 */
@DisplayName("Clinic administration")
class ClinicStaffTest extends ClinicTestBase {

    /** Emails are unique per run: the audit log (and so the sign-in throttle) remembers earlier runs. */
    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    @Autowired ConsentService consents;
    @Autowired QueueService queue;

    private User platformAdmin() {
        return user("Platform Ops", Role.ADMIN);
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password))));
    }

    @Test
    @DisplayName("the platform creates a clinic and its administrator; the temporary password works once, to change it")
    void createdAdministratorMustChangeTheTemporaryPassword() throws Exception {
        JsonNode created = body(postAs(platformAdmin(), "/api/v1/admin/clinics", Map.of(
                "clinic", Map.of("name", "Cedar Care", "address", "9 Cedar Lane", "city", "Mysuru",
                        "latitude", 12.3, "longitude", 76.6, "phone", "+918212345678", "timezone", "Asia/Kolkata"),
                "adminFullName", "Chitra Cedar", "adminEmail", "Chitra." + RUN + "@Cedar.Example"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clinic.name").value("Cedar Care"))
                .andExpect(jsonPath("$.admin.role").value("CLINIC_ADMIN")));
        String temporary = created.at("/admin/temporaryPassword").asText();
        UUID clinicId = UUID.fromString(created.at("/clinic/id").asText());
        assertThat(temporary).hasSize(14);
        assertThat(audit("CLINIC_CREATED", clinicId)).isEqualTo(1);
        // The password is shown once and kept nowhere: not in the audit detail either.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ? AND detail::text LIKE ?",
                Integer.class, clinicId.toString(), "%" + temporary + "%")).isZero();

        // Signing in works, and says the password must change.
        JsonNode session = body(login("chitra." + RUN + "@cedar.example", temporary).andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true)));
        String token = "Bearer " + session.get("accessToken").asText();

        // Until it has, nothing else is allowed.
        mvc.perform(get("/api/v1/clinics/me").header("Authorization", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        mvc.perform(get("/api/v1/doctors/me/profile").header("Authorization", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));

        // A wrong current password, and an unchanged one, are refused.
        change(token, "not-the-password", "a-brand-new-password").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INCORRECT_PASSWORD"));
        change(token, temporary, temporary).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PASSWORD_UNCHANGED"));

        JsonNode changed = body(change(token, temporary, "a-brand-new-password").andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false)));
        assertThat(audit("PASSWORD_CHANGED", session.get("userId").asText())).isEqualTo(1);

        // The fresh session works; the temporary password, and the old session's refresh token, do not.
        mvc.perform(get("/api/v1/clinics/me").header("Authorization", "Bearer " + changed.get("accessToken").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Cedar Care"));
        login("chitra." + RUN + "@cedar.example", temporary).andExpect(status().isUnauthorized());
        login("chitra." + RUN + "@cedar.example", "a-brand-new-password").andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false));
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("refreshToken", session.get("refreshToken").asText()))))
                .andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions change(String bearerToken, String current, String next)
            throws Exception {
        return mvc.perform(post("/api/v1/account/change-password").header("Authorization", bearerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("currentPassword", current, "newPassword", next))));
    }

    @Test
    @DisplayName("the administrator edits the clinic's profile; bad locations and time zones are refused; every change is audited")
    void profileUpdate() throws Exception {
        putAs(adminA, "/api/v1/clinics/me", Map.of("name", "Alder Clinic and Day Hospital", "address", "1 Test Road",
                "city", "Bengaluru", "latitude", 12.97, "longitude", 77.64, "phone", "+918012345678",
                "email", "Front@Alder.Example", "timezone", "Asia/Kolkata"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alder Clinic and Day Hospital"))
                .andExpect(jsonPath("$.email").value("front@alder.example"));
        assertThat(auditForClinic("CLINIC_UPDATED", clinicA)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT jsonb_exists(detail -> 'fields', 'name') FROM audit_log WHERE action = 'CLINIC_UPDATED'"
                + " AND detail ->> 'clinicId' = ?", Boolean.class, clinicA.getId().toString())).isTrue();

        putAs(adminA, "/api/v1/clinics/me", Map.of("name", "X", "latitude", 12.0))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("LOCATION_INCOMPLETE"));
        putAs(adminA, "/api/v1/clinics/me", Map.of("name", "X", "timezone", "Mars/Olympus"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UNKNOWN_TIMEZONE"));
        putAs(adminA, "/api/v1/clinics/me", Map.of("name", " "))
                .andExpect(status().isBadRequest());

        // A receptionist may read the clinic's profile (the desk shows its name) but not change it.
        getAs(deskA, "/api/v1/clinics/me").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alder Clinic and Day Hospital"));
        // Clinic B's profile is untouched.
        assertThat(jdbc.queryForObject("SELECT name FROM clinics WHERE id = ?", String.class, clinicB.getId()))
                .isEqualTo("Birch Hospital");
    }

    @Test
    @DisplayName("a doctor added by the clinic belongs to it, starts unverified, and appears in the directory once verified")
    void addDoctor() throws Exception {
        Map<String, Object> request = Map.of("fullName", "Dr. Nandini Rao", "email", "Nandini@Alder.Example",
                "specialization", "Dermatology", "medicalCouncil", "Karnataka Medical Council",
                "registrationNumber", "ka-derm-77001", "qualification", "MD Dermatology",
                "yearsExperience", 8, "consultationFee", 700);
        JsonNode created = body(postAs(adminA, "/api/v1/clinics/me/doctors", request)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("DOCTOR")));
        UUID userId = UUID.fromString(created.get("userId").asText());
        UUID doctorId = jdbc.queryForObject("SELECT id FROM doctors WHERE user_id = ?", UUID.class, userId);

        assertThat(jdbc.queryForObject("SELECT clinic_id FROM doctors WHERE id = ?", UUID.class, doctorId))
                .isEqualTo(clinicA.getId());
        assertThat(jdbc.queryForObject("SELECT verified_at IS NULL FROM doctors WHERE id = ?", Boolean.class, doctorId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT must_change_password FROM users WHERE id = ?", Boolean.class, userId)).isTrue();
        assertThat(audit("CLINIC_DOCTOR_ADDED", doctorId)).isEqualTo(1);

        // Not listed until the platform has checked the registration number.
        mvc.perform(get("/api/v1/doctors").param("clinicId", clinicA.getId().toString()))
                .andExpect(jsonPath("$.content[*].fullName", not(hasItem("Dr. Nandini Rao"))));
        postAs(platformAdmin(), "/api/v1/admin/doctors/" + doctorId + "/verify", null).andExpect(status().isOk());
        mvc.perform(get("/api/v1/doctors").param("clinicId", clinicA.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].fullName", hasItem("Dr. Nandini Rao")))
                .andExpect(jsonPath("$.content[*].fullName", hasItem("Dr. Alder")))
                .andExpect(jsonPath("$.content[*].fullName", not(hasItem("Dr. Birch"))));

        // The same registration number, or email, cannot be added twice.
        postAs(adminA, "/api/v1/clinics/me/doctors", request).andExpect(status().isConflict());
        postAs(adminB, "/api/v1/clinics/me/doctors", Map.of("fullName", "Dr. Other", "email", "other@birch.example",
                "specialization", "Dermatology", "medicalCouncil", "KMC", "registrationNumber", "KA-DERM-77001",
                "qualification", "MD", "yearsExperience", 1, "consultationFee", 100))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REGISTRATION_TAKEN"));
        postAs(adminA, "/api/v1/clinics/me/doctors", Map.of("fullName", "Dr. Bad", "email", "bad@alder.example",
                "specialization", "Astrology", "medicalCouncil", "KMC", "registrationNumber", "KA-X-1",
                "qualification", "MD", "yearsExperience", 1, "consultationFee", 100))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UNKNOWN_SPECIALITY"));
    }

    @Test
    @DisplayName("the directory lists clinics, filters doctors by clinic, shows the clinic's details, and still lists independent doctors")
    void directory() throws Exception {
        Doctor independent = doctorRepository.saveAndFlush(Doctor.builder().user(user("Dr. Solo", Role.DOCTOR))
                .specialization("General Medicine").licenseNumber("LIC-" + UUID.randomUUID().toString().substring(0, 8))
                .consultationFee(new java.math.BigDecimal("300.00")).clinicName("Solo Practice")
                .clinicAddress("5 Solo Street").build());

        mvc.perform(get("/api/v1/clinics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("Alder Clinic")))
                .andExpect(jsonPath("$[*].name", hasItem("Birch Hospital")))
                .andExpect(jsonPath("$[?(@.name == 'Alder Clinic')].doctors").value(hasItem(1)));
        mvc.perform(get("/api/v1/clinics/" + clinicA.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.address").value("1 Test Road"));
        mvc.perform(get("/api/v1/clinics/" + UUID.randomUUID())).andExpect(status().isNotFound());

        // The clinic's name and address are what the directory shows for its doctors.
        mvc.perform(get("/api/v1/doctors").param("clinicId", clinicA.getId().toString()))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].clinicName").value("Alder Clinic"))
                .andExpect(jsonPath("$.content[0].clinicId").value(clinicA.getId().toString()))
                .andExpect(jsonPath("$.content[0].clinicAddress").value("1 Test Road"));
        // Without the filter everyone is listed, the independent doctor with their own details.
        mvc.perform(get("/api/v1/doctors"))
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.content[?(@.fullName == 'Dr. Solo')].clinicName").value(hasItem("Solo Practice")))
                .andExpect(jsonPath("$.content[?(@.fullName == 'Dr. Solo' && @.clinicId == null)]").exists());
        // The search box finds a clinic by name, too.
        mvc.perform(get("/api/v1/doctors").param("q", "hospital"))
                .andExpect(jsonPath("$.content[*].fullName", hasItem("Dr. Birch")))
                .andExpect(jsonPath("$.content[*].fullName", not(hasItem("Dr. Alder"))));
        mvc.perform(get("/api/v1/doctors/" + doctorA.getId()))
                .andExpect(jsonPath("$.clinicName").value("Alder Clinic"));

        // A deactivated clinic and its doctors drop out of the directory.
        putAs(platformAdmin(), "/api/v1/admin/clinics/" + clinicA.getId() + "/active", Map.of("active", false))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/clinics")).andExpect(jsonPath("$[*].name", not(hasItem("Alder Clinic"))));
        mvc.perform(get("/api/v1/doctors")).andExpect(jsonPath("$.content[*].fullName", not(hasItem("Dr. Alder"))));
        assertThat(independent.getId()).isNotNull();
    }

    @Test
    @DisplayName("a doctor who belongs to a clinic cannot overwrite the clinic's details from their practice page")
    void clinicDoctorsPracticePageDoesNotOverwriteTheClinic() throws Exception {
        putAs(doctorA.getUser(), "/api/v1/doctors/me/practice", Map.of("consultationFee", 650, "insurers", java.util.List.of(),
                "prices", java.util.List.of(), "yearsExperience", 6,
                "clinic", Map.of("name", "My Own Name", "address", "Elsewhere", "latitude", 1.0, "longitude", 2.0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clinicManaged").value(true))
                .andExpect(jsonPath("$.clinic.name").value("Alder Clinic"))
                .andExpect(jsonPath("$.consultationFee").value(650.00));
        assertThat(jdbc.queryForObject("SELECT clinic_name IS NULL FROM doctors WHERE id = ?", Boolean.class,
                doctorA.getId())).isTrue();
    }

    @Test
    @DisplayName("a receptionist is added with a temporary password, reset on request, and turned off at once")
    void receptionistLifecycle() throws Exception {
        JsonNode created = body(postAs(adminA, "/api/v1/clinics/me/receptionists",
                Map.of("fullName", "Rita Reception", "email", "rita." + RUN + "@alder.example", "phone", "9876543210"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("RECEPTIONIST")));
        UUID userId = UUID.fromString(created.get("userId").asText());
        String temporary = created.get("temporaryPassword").asText();
        assertThat(audit("CLINIC_STAFF_ADDED", userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT clinic_id FROM clinic_staff WHERE user_id = ?", UUID.class, userId))
                .isEqualTo(clinicA.getId());
        postAs(adminA, "/api/v1/clinics/me/receptionists",
                Map.of("fullName", "Rita Again", "email", "rita." + RUN + "@alder.example")).andExpect(status().isConflict());

        // First sign-in: must change, then the desk opens.
        String token = "Bearer " + body(login("rita." + RUN + "@alder.example", temporary).andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true))).get("accessToken").asText();
        mvc.perform(get("/api/v1/clinics/me/desk/today").header("Authorization", token)).andExpect(status().isForbidden());
        String fresh = "Bearer " + body(change(token, temporary, "rita-chose-this-one").andExpect(status().isOk()))
                .get("accessToken").asText();
        mvc.perform(get("/api/v1/clinics/me/desk/today").header("Authorization", fresh)).andExpect(status().isOk());

        // The administrator resets it: the old password stops working and the next sign-in must change it again.
        JsonNode reset = body(postAs(adminA, "/api/v1/clinics/me/staff/" + userId + "/reset-password", null)
                .andExpect(status().isOk()));
        assertThat(reset.get("temporaryPassword").asText()).isNotEqualTo(temporary).hasSize(14);
        assertThat(audit("CLINIC_STAFF_PASSWORD_RESET", userId)).isEqualTo(1);
        login("rita." + RUN + "@alder.example", "rita-chose-this-one").andExpect(status().isUnauthorized());
        login("rita." + RUN + "@alder.example", reset.get("temporaryPassword").asText()).andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));

        // Turned off: the session in hand stops working, and sign-in is refused.
        String live = "Bearer " + body(login("rita." + RUN + "@alder.example", reset.get("temporaryPassword").asText())
                .andExpect(status().isOk())).get("accessToken").asText();
        postAs(adminA, "/api/v1/clinics/me/staff/" + userId + "/disable", null).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/clinics/me/desk/today").header("Authorization", live)).andExpect(status().isUnauthorized());
        login("rita." + RUN + "@alder.example", reset.get("temporaryPassword").asText()).andExpect(status().isUnprocessableEntity());
        postAs(adminA, "/api/v1/clinics/me/staff/" + userId + "/enable", null).andExpect(status().isNoContent());
        assertThat(audit("CLINIC_STAFF_DISABLED", userId)).isEqualTo(1);
        assertThat(audit("CLINIC_STAFF_ENABLED", userId)).isEqualTo(1);
        login("rita." + RUN + "@alder.example", reset.get("temporaryPassword").asText()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("an administrator's own account is not managed from the staff screen, by themselves or a colleague")
    void administratorsAreProtected() throws Exception {
        User second = staff(clinicA, Role.CLINIC_ADMIN, "Second Admin");
        for (User target : new User[]{adminA, second}) {
            postAs(adminA, "/api/v1/clinics/me/staff/" + target.getId() + "/reset-password", null)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("ADMIN_ACCOUNT_PROTECTED"));
            postAs(adminA, "/api/v1/clinics/me/staff/" + target.getId() + "/disable", null)
                    .andExpect(status().isUnprocessableEntity());
        }
        assertThat(jdbc.queryForObject("SELECT enabled FROM users WHERE id = ?", Boolean.class, second.getId())).isTrue();
    }

    @Test
    @DisplayName("the roster lists doctors, receptionists and administrators with whether each doctor is verified")
    void roster() throws Exception {
        getAs(adminA, "/api/v1/clinics/me/staff").andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].role").value("CLINIC_ADMIN"))
                .andExpect(jsonPath("$[?(@.role == 'DOCTOR')].verified").value(hasItem(true)))
                .andExpect(jsonPath("$[?(@.role == 'RECEPTIONIST')].fullName").value(hasItem("Anu Desk")));
    }

    @Test
    @DisplayName("the report adds visits, no-shows, cancellations, walk-ins and desk bookings per day, for the range asked")
    void report() throws Exception {
        AppointmentSlot a = slot(doctorA, Duration.ofHours(-3));
        AppointmentSlot b = slot(doctorA, Duration.ofHours(-5));
        AppointmentSlot c = slot(doctorA, Duration.ofHours(-7));
        AppointmentSlot d = slot(doctorA, Duration.ofHours(-9));
        setStatus(visit(a, patient("Seen One")), AppointmentStatus.COMPLETED);
        setStatus(visit(b, patient("Seen Two")), AppointmentStatus.COMPLETED);
        setStatus(visit(c, patient("Missed One")), AppointmentStatus.NO_SHOW);
        Appointment cancelled = visit(d, patient("Cancelled One"));
        cancelled.cancel(clock.instant(), "unwell");
        appointmentRepository.saveAndFlush(cancelled);
        jdbc.update("UPDATE appointments SET booked_by_user_id = ? WHERE slot_id = ?", deskA.getId(), a.getId());
        Patient w1 = patient("Walk One");
        Patient w2 = patient("Walk Two");
        QueueService.Token t1 = queue.join(doctorA.getId(), w1, null);
        queue.join(doctorA.getId(), w2, null);
        jdbc.update("UPDATE queue_tokens SET status = 'SEEN' WHERE id = ?", t1.id());

        String day = dayOf(a).toString();
        // The nine hours before now can straddle midnight; the range is wide enough to hold them all.
        String from = dayOf(d).toString();
        String to = java.time.LocalDate.now(clock.withZone(INDIA)).toString();
        JsonNode report = body(getAs(adminA, "/api/v1/clinics/me/report?from=" + from + "&to=" + to)
                .andExpect(status().isOk()).andExpect(jsonPath("$.timezone").value("Asia/Kolkata")));
        JsonNode totals = report.get("totals");
        assertThat(totals.get("scheduled").asInt()).isEqualTo(3);     // cancelled ones are not scheduled visits
        assertThat(totals.get("completed").asInt()).isEqualTo(2);
        assertThat(totals.get("noShows").asInt()).isEqualTo(1);
        assertThat(totals.get("cancellations").asInt()).isEqualTo(1);
        assertThat(totals.get("walkIns").asInt()).isEqualTo(2);
        assertThat(totals.get("walkInsSeen").asInt()).isEqualTo(1);
        assertThat(totals.get("bookedByDesk").asInt()).isEqualTo(1);
        // Every day of the range is a row, quiet ones as zeros.
        assertThat(report.get("days").size()).isBetween(1, 2);
        assertThat(report.get("days").findValuesAsText("date")).contains(day);
        assertThat(auditForClinic("CLINIC_REPORT_VIEWED", clinicA)).isEqualTo(1);

        // A range that is backwards or too long is refused.
        getAs(adminA, "/api/v1/clinics/me/report?from=2030-01-10&to=2030-01-01")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        getAs(adminA, "/api/v1/clinics/me/report?from=2030-01-01&to=2030-12-31")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        getAs(adminA, "/api/v1/clinics/me/report?from=2030-01-01&to=2030-01-03")
                .andExpect(status().isOk()).andExpect(jsonPath("$.days", hasSize(3)))
                .andExpect(jsonPath("$.totals.scheduled").value(0));
    }

    private void setStatus(Appointment visit, AppointmentStatus status) {
        visit.setStatus(status);
        appointmentRepository.saveAndFlush(visit);
    }

    @Test
    @DisplayName("clinic staff agree to the terms and privacy notice like doctors and chemists, not the health-data text")
    void consentRequiredForNewRoles() {
        for (Role role : new Role[]{Role.CLINIC_ADMIN, Role.RECEPTIONIST}) {
            assertThat(consents.requiredFor(role))
                    .containsExactlyInAnyOrder(ConsentType.TERMS_OF_SERVICE, ConsentType.PRIVACY_NOTICE);
        }
        assertThat(consents.requiredFor(Role.PATIENT)).contains(ConsentType.HEALTH_DATA_PROCESSING);
        assertThat(consents.requiredFor(Role.ADMIN)).isEmpty();
    }

    @Test
    @DisplayName("the database check accepts the new roles and still refuses an invented one")
    void roleCheckConstraint() {
        for (String role : new String[]{"CLINIC_ADMIN", "RECEPTIONIST"}) {
            jdbc.update("""
                    INSERT INTO users (email, password_hash, full_name, role) VALUES (?, 'x', 'Constraint Check', ?)
                    """, role.toLowerCase() + "@constraint.example", role);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO users (email, password_hash, full_name, role) VALUES ('nope@constraint.example', 'x', 'Nope', 'JANITOR')
                """)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
