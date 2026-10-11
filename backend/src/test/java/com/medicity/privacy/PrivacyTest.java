package com.medicity.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicity.audit.AuditLog;
import com.medicity.jobs.JobLock;
import com.medicity.jobs.LocationRetentionJob;
import com.medicity.request.NetworkTestSupport;
import com.medicity.scheduling.Appointment;
import com.medicity.scheduling.AppointmentSlot;
import com.medicity.scheduling.AppointmentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Consent records, the data export, account deletion and the location retention job. */
@DisplayName("Privacy: consent, export, deletion, retention")
class PrivacyTest extends NetworkTestSupport {

    private static final String PASSWORD = "meera-password-2026";

    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired LocationRetentionJob retention;
    @Autowired JobLock jobLock;
    @Autowired AuditLog auditLog;
    @Autowired ConsentService consentService;
    @Autowired AccountDeletionService deletion;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;

    @BeforeEach
    void givenMeeraHasAPassword() {
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", passwordEncoder.encode(PASSWORD), meeraUser.getId());
    }

    // --- consent --------------------------------------------------------------------

    @Test
    @DisplayName("patient sign-up records terms, privacy notice and health-data consent at the current versions")
    void signUpRecordsConsent() throws Exception {
        String email = "new-" + UUID.randomUUID() + "@privacy.test";
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(registration(email, true, true)))
                .andExpect(status().isCreated());

        UUID user = userRepository.findByEmail(email).orElseThrow().getId();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT consent_type, policy_version, capacity, source, subject_patient_id
                FROM consent_records WHERE user_id = ? ORDER BY consent_type""", user);
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(r -> r.get("consent_type")).containsExactly(
                "HEALTH_DATA_PROCESSING", "PRIVACY_NOTICE", "TERMS_OF_SERVICE");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("capacity")).isEqualTo("SELF");
            assertThat(r.get("source")).isEqualTo("SIGN_UP");
            assertThat(r.get("subject_patient_id")).isNull();
        });
        assertThat(rows.get(0).get("policy_version")).isEqualTo(consentService.currentVersion(ConsentType.HEALTH_DATA_PROCESSING));
    }

    @Test
    @DisplayName("sign-up without either agreement is refused with a field error, and leaves no account")
    void signUpNeedsBothAgreements() throws Exception {
        String noHealth = "nohealth-" + UUID.randomUUID() + "@privacy.test";
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(registration(noHealth, true, false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.acceptedHealthData").exists());
        String noTerms = "noterms-" + UUID.randomUUID() + "@privacy.test";
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(registration(noTerms, false, true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.acceptedTerms").exists());

        assertThat(userRepository.findByEmail(noHealth)).isEmpty();
        assertThat(userRepository.findByEmail(noTerms)).isEmpty();
    }

    @Test
    @DisplayName("a chemist's sign-up records terms and privacy only, and is refused without them")
    void chemistConsent() throws Exception {
        String body = """
                {"email":"%s","password":"a-long-enough-password","fullName":"Ravi Kumar","acceptedTerms":%s,
                 "store":{"name":"Sai","licenceNumber":"KA-PR-%s","phone":"9800000001","addressLine":"1 Road","city":"Bengaluru",
                          "latitude":12.97,"longitude":77.64,"opensAt":"08:00","closesAt":"22:00","open24h":false,"holdHours":3}}
                """;
        mvc.perform(post("/api/v1/auth/register/chemist").contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("nope-" + UUID.randomUUID() + "@privacy.test", false, "1")))
                .andExpect(status().isBadRequest());

        String email = "chem-" + UUID.randomUUID() + "@privacy.test";
        mvc.perform(post("/api/v1/auth/register/chemist").contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(email, true, UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isCreated());
        UUID user = userRepository.findByEmail(email).orElseThrow().getId();
        assertThat(jdbc.queryForList("SELECT consent_type FROM consent_records WHERE user_id = ? ORDER BY 1",
                String.class, user)).containsExactly("PRIVACY_NOTICE", "TERMS_OF_SERVICE");
    }

    @Test
    @DisplayName("when a policy version changes the account is asked again, and agreeing clears it; old rows stay")
    void policyChangeAsksAgain() throws Exception {
        mvc.perform(get("/api/v1/consents/me").header("Authorization", bearer(meeraUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(true));   // a fixture account never agreed to anything

        mvc.perform(post("/api/v1/consents/me").header("Authorization", bearer(meeraUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"types\":[\"TERMS_OF_SERVICE\",\"PRIVACY_NOTICE\",\"HEALTH_DATA_PROCESSING\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(false));

        // The texts change: what was agreed to is now an older version.
        jdbc.update("UPDATE consent_records SET policy_version = 'older-version' WHERE user_id = ?", meeraUser.getId());
        mvc.perform(get("/api/v1/consents/me").header("Authorization", bearer(meeraUser)))
                .andExpect(jsonPath("$.pending").value(true))
                .andExpect(jsonPath("$.items[?(@.type=='TERMS_OF_SERVICE')].acceptedVersion").value("older-version"));

        mvc.perform(post("/api/v1/consents/me").header("Authorization", bearer(meeraUser))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"types\":[\"TERMS_OF_SERVICE\"]}"))
                .andExpect(jsonPath("$.pending").value(true));   // two texts still outstanding
        mvc.perform(post("/api/v1/consents/me").header("Authorization", bearer(meeraUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"types\":[\"PRIVACY_NOTICE\",\"HEALTH_DATA_PROCESSING\"]}"))
                .andExpect(jsonPath("$.pending").value(false));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM consent_records WHERE user_id = ?", Integer.class,
                meeraUser.getId())).isEqualTo(6);
        // Agreeing twice is one fact.
        mvc.perform(post("/api/v1/consents/me").header("Authorization", bearer(meeraUser))
                .contentType(MediaType.APPLICATION_JSON).content("{\"types\":[\"TERMS_OF_SERVICE\"]}"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consent_records WHERE user_id = ?", Integer.class,
                meeraUser.getId())).isEqualTo(6);
    }

    @Test
    @DisplayName("a doctor cannot be asked for a health-data consent that does not apply to the role")
    void consentMustApplyToTheRole() throws Exception {
        mvc.perform(post("/api/v1/consents/me").header("Authorization", bearer(doctorUser))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"types\":[\"HEALTH_DATA_PROCESSING\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONSENT_NOT_APPLICABLE"));
    }

    @Test
    @DisplayName("adding a family member records the account holder's consent for them, as guardian of a minor or representative of an adult")
    void familyConsentIsOnBehalf() throws Exception {
        UUID child = addMember("Aarav Nair", "2019-06-02", true);
        UUID adult = addMember("Lata Nair", "1960-03-01", true);

        assertThat(consentCapacity(child)).isEqualTo("GUARDIAN_OF_MINOR");
        assertThat(consentCapacity(adult)).isEqualTo("REPRESENTATIVE_OF_ADULT");
        assertThat(jdbc.queryForObject("""
                SELECT user_id FROM consent_records WHERE subject_patient_id = ?""", UUID.class, child))
                .isEqualTo(meeraUser.getId());

        mvc.perform(post("/api/v1/patients/me/family").header("Authorization", bearer(meeraUser))
                        .contentType(MediaType.APPLICATION_JSON).content(memberJson("No Consent", "2019-06-02", false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.guardianConsent").exists());
    }

    // --- export ---------------------------------------------------------------------

    @Test
    @DisplayName("the export holds my profile, my family's, visits and prescriptions, as a download; nothing of anyone else or any secret")
    void exportIsComplete() throws Exception {
        UUID aarav = addMember("Aarav Nair", "2019-06-02", true);
        jdbc.update("UPDATE patients SET allergies = 'Penicillin' WHERE id = ?", aarav);
        jdbc.update("UPDATE patients SET city = 'Bengaluru' WHERE user_id = ?", meeraUser.getId());
        mvc.perform(post("/api/v1/consents/me").header("Authorization", bearer(meeraUser))
                .contentType(MediaType.APPLICATION_JSON).content("{\"types\":[\"TERMS_OF_SERVICE\"]}"));

        String body = mvc.perform(get("/api/v1/patients/me/export").header("Authorization", bearer(meeraUser)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString(".json")))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andReturn().getResponse().getContentAsString();
        JsonNode export = json.readTree(body);

        assertThat(export.path("account").path("email").asText()).isEqualTo(meeraUser.getEmail());
        assertThat(export.path("account").path("fullName").asText()).isEqualTo("Meera Nair");
        List<String> people = export.path("people").findValuesAsText("fullName");
        assertThat(people).contains("Aarav Nair");
        assertThat(export.path("people").toString()).contains("Penicillin").contains("Bengaluru");
        JsonNode prescription = export.path("prescriptions").get(0);
        assertThat(prescription.path("diagnosis").asText()).isEqualTo("Reflux");
        assertThat(prescription.path("doctorName").asText()).isEqualTo("Dr. Anjali Rao");
        assertThat(prescription.path("medicines")).hasSize(2);
        assertThat(export.path("appointments")).hasSize(1);
        assertThat(export.path("consents")).isNotEmpty();

        // Secrets, other people's data.
        assertThat(body).doesNotContain("password").doesNotContain("passwordHash").doesNotContain("noop");
        assertThat(body).doesNotContain("Mallory");
        assertThat(body).doesNotContain(otherUser.getEmail());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'DATA_EXPORTED' AND entity_id = ?",
                Integer.class, meeraUser.getId().toString())).isEqualTo(1);
    }

    @Test
    @DisplayName("the export needs a patient sign-in, and a patient only ever receives their own")
    void exportIsPrivate() throws Exception {
        mvc.perform(get("/api/v1/patients/me/export")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/patients/me/export").header("Authorization", bearer(doctorUser)))
                .andExpect(status().isForbidden());
        // Mallory's export has her account and none of Meera's records.
        String body = mvc.perform(get("/api/v1/patients/me/export").header("Authorization", bearer(otherUser)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).path("prescriptions")).isEmpty();
        assertThat(body).doesNotContain("Meera").doesNotContain("Reflux");
    }

    // --- deletion -------------------------------------------------------------------

    @Test
    @DisplayName("a wrong password deletes nothing, and is counted like a failed sign-in")
    void wrongPasswordDeletesNothing() throws Exception {
        mvc.perform(post("/api/v1/patients/me/delete-account").header("Authorization", bearer(meeraUser))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"not-it\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INCORRECT_PASSWORD"));

        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL AND enabled FROM users WHERE id = ?", Boolean.class,
                meeraUser.getId())).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_log WHERE action = 'LOGIN_FAILED' AND detail ->> 'email' = ?""",
                Integer.class, meeraUser.getEmail())).isEqualTo(1);
    }

    @Test
    @DisplayName("deleting anonymises the account and family, ends sessions, cancels upcoming visits and keeps the clinical record without a name")
    void deletionAnonymisesAndKeepsTheRecord() throws Exception {
        UUID aarav = addMember("Aarav Nair", "2019-06-02", true);
        jdbc.update("UPDATE patients SET allergies = 'Penicillin', address_line = '12 MG Road', "
                + "home_latitude = 12.9, home_longitude = 77.6, blood_group = 'O+' WHERE id = ?", aarav);
        jdbc.update("UPDATE patients SET allergies = 'Dust', city = 'Bengaluru', emergency_contact = '9800000000', "
                + "height_cm = 160 WHERE user_id = ?", meeraUser.getId());
        jdbc.update("UPDATE users SET phone = '9811111111', login_phone = '+919811111111' WHERE id = ?", meeraUser.getId());
        UUID meeraPatient = meera.getId();

        Appointment upcoming = bookedVisit(meera, clock.instant().plus(3, ChronoUnit.DAYS));
        UUID question = ask(3000);
        jdbc.update("INSERT INTO notifications (user_id, event_id, kind, title, body) VALUES (?, gen_random_uuid(), 'X', 'T', 'B')",
                meeraUser.getId());
        String pair = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(meeraUser.getEmail(), PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String refresh = json.readTree(pair).path("refreshToken").asText();
        String access = json.readTree(pair).path("accessToken").asText();
        int prescriptionsBefore = count("SELECT count(*) FROM prescriptions");
        String email = meeraUser.getEmail();

        mvc.perform(post("/api/v1/patients/me/delete-account").header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visitsCancelled").value(1))
                .andExpect(jsonPath("$.questionsClosed").value(1))
                .andExpect(jsonPath("$.patientsAnonymised").value(2));

        // The account: nobody's name, address, number or password.
        Map<String, Object> user = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", meeraUser.getId());
        assertThat(user.get("full_name")).isEqualTo("Deleted user");
        assertThat((String) user.get("email")).endsWith("@deleted.invalid").doesNotContain("meera");
        assertThat(user.get("phone")).isNull();
        assertThat(user.get("login_phone")).isNull();
        assertThat(user.get("enabled")).isEqualTo(false);
        assertThat(user.get("deleted_at")).isNotNull();
        assertThat(passwordEncoder.matches(PASSWORD, (String) user.get("password_hash"))).isFalse();

        // Health and contact details, the account holder's and the family member's.
        Map<String, Object> self = jdbc.queryForMap("SELECT * FROM patients WHERE id = ?", meeraPatient);
        assertThat(self.get("allergies")).isNull();
        assertThat(self.get("city")).isNull();
        assertThat(self.get("emergency_contact")).isNull();
        assertThat(self.get("height_cm")).isNull();
        assertThat(self.get("date_of_birth").toString()).isEqualTo("1994-01-01");   // year only
        Map<String, Object> child = jdbc.queryForMap("SELECT * FROM patients WHERE id = ?", aarav);
        assertThat(child.get("full_name")).isEqualTo("Deleted family member");
        assertThat(child.get("allergies")).isNull();
        assertThat(child.get("address_line")).isNull();
        assertThat(child.get("home_latitude")).isNull();
        assertThat(child.get("blood_group")).isNull();

        // Sessions are over, whichever way they are tried.
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/patients/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isUnauthorized());
        assertThat(count("SELECT count(*) FROM refresh_tokens WHERE user_id = '" + meeraUser.getId() + "'")).isZero();
        assertThat(count("SELECT count(*) FROM notifications WHERE user_id = '" + meeraUser.getId() + "'")).isZero();

        // Upcoming visit cancelled and its slot free again; the question closed with its place cleared.
        assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, upcoming.getId()))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM medicine_requests WHERE id = ?", String.class, question))
                .isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT latitude IS NULL AND longitude IS NULL FROM medicine_requests WHERE id = ?",
                Boolean.class, question)).isTrue();

        // What the law and the audit need is still here: prescription, its visit, consent, the audit rows.
        assertThat(count("SELECT count(*) FROM prescriptions")).isEqualTo(prescriptionsBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM prescription_items", Integer.class)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM appointments WHERE patient_id = '" + meeraPatient + "' AND status = 'COMPLETED'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM consent_records WHERE user_id = '" + meeraUser.getId() + "'")).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'ACCOUNT_DELETED' AND entity_id = ?",
                Integer.class, meeraUser.getId().toString())).isEqualTo(1);

        // The account is gone for good: the same token is not an account any more.
        mvc.perform(post("/api/v1/patients/me/delete-account").header("Authorization", bearer(meeraUser))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the delete route is a patient's (a doctor gets 403), and the service itself refuses staff accounts")
    void staffAccountsAreHandledByAPerson() throws Exception {
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", passwordEncoder.encode(PASSWORD), doctorUser.getId());
        mvc.perform(post("/api/v1/patients/me/delete-account").header("Authorization", bearer(doctorUser))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isForbidden());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> deletion.delete(doctorUser.getId(), PASSWORD))
                .isInstanceOf(com.medicity.common.ConflictException.class)
                .hasMessageContaining("grievance officer");
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM users WHERE id = ?", Boolean.class,
                doctorUser.getId())).isTrue();
    }

    @Test
    @DisplayName("the password check is throttled like sign-in: after five misses the right password is refused too")
    void deletionPasswordIsThrottled() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/patients/me/delete-account").header("Authorization", bearer(meeraUser))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"wrong-" + i + "\"}"))
                    .andExpect(status().isUnprocessableEntity());
        }
        mvc.perform(post("/api/v1/patients/me/delete-account").header("Authorization", bearer(meeraUser))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isTooManyRequests());
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM users WHERE id = ?", Boolean.class,
                meeraUser.getId())).isTrue();
    }

    // --- retention ------------------------------------------------------------------

    @Test
    @DisplayName("the retention job clears the place of questions closed longer than the period ago, and nothing else")
    void retentionClears() {
        UUID old = closedQuestion("CLOSED", 31);
        UUID expiredOld = closedQuestion("EXPIRED", 45);
        UUID recent = closedQuestion("CLOSED", 5);
        UUID open = openQuestion();

        int auditedBefore = count("SELECT count(*) FROM audit_log WHERE action = 'LOCATION_RETENTION_APPLIED'");
        assertThat(retention.run()).isEqualTo(2);

        assertThat(location(old)).isNull();
        assertThat(location(expiredOld)).isNull();
        assertThat(location(recent)).isEqualTo(LAT + "," + LNG);
        assertThat(location(open)).isEqualTo(LAT + "," + LNG);
        assertThat(jdbc.queryForObject("SELECT location_minimised_at IS NOT NULL FROM medicine_requests WHERE id = ?",
                Boolean.class, old)).isTrue();
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'LOCATION_RETENTION_APPLIED'"))
                .isEqualTo(auditedBefore + 1);

        // Run again: nothing left to do, no second audit row.
        assertThat(retention.run()).isZero();
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'LOCATION_RETENTION_APPLIED'"))
                .isEqualTo(auditedBefore + 1);
    }

    @Test
    @DisplayName("in coarsen mode the place is rounded to the configured decimals, once")
    void retentionCoarsens() {
        UUID old = closedQuestion("CLOSED", 31);
        LocationRetentionJob coarsening = new LocationRetentionJob(jdbc, jobLock, auditLog, clock,
                Duration.ofDays(30), "coarsen", 1);

        assertThat(inTransaction(coarsening)).isEqualTo(1);
        assertThat(location(old)).isEqualTo("13,77.6");
        assertThat(inTransaction(coarsening)).isZero();
    }

    @Test
    @DisplayName("a retention period or mode that makes no sense stops the application starting")
    void retentionSettingsAreChecked() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                new LocationRetentionJob(jdbc, jobLock, auditLog, clock, Duration.ZERO, "clear", 1));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                new LocationRetentionJob(jdbc, jobLock, auditLog, clock, Duration.ofDays(1), "clear", 5));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                new LocationRetentionJob(jdbc, jobLock, auditLog, clock, Duration.ofDays(1), "delete", 1));
    }

    // --- helpers --------------------------------------------------------------------

    private static String registration(String email, boolean terms, boolean health) {
        return """
                {"email":"%s","password":"a-long-enough-password","fullName":"New Patient","dateOfBirth":"1990-01-01",
                 "acceptedTerms":%s,"acceptedHealthData":%s}
                """.formatted(email, terms, health);
    }

    private static String memberJson(String name, String dob, boolean consent) {
        return "{\"fullName\":\"%s\",\"relationship\":\"OTHER\",\"dateOfBirth\":\"%s\",\"gender\":\"MALE\",\"guardianConsent\":%s}"
                .formatted(name, dob, consent);
    }

    private UUID addMember(String name, String dob, boolean consent) throws Exception {
        String body = mvc.perform(post("/api/v1/patients/me/family").header("Authorization", bearer(meeraUser))
                        .contentType(MediaType.APPLICATION_JSON).content(memberJson(name, dob, consent)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("patientId").asText());
    }

    private String consentCapacity(UUID patientId) {
        return jdbc.queryForObject("SELECT capacity FROM consent_records WHERE subject_patient_id = ?", String.class, patientId);
    }

    private int inTransaction(LocationRetentionJob job) {
        Integer handled = transactions.execute(tx -> job.run());
        return handled == null ? -1 : handled;
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private Appointment bookedVisit(com.medicity.patient.Patient patient, Instant start) {
        AppointmentSlot slot = slotRepository.save(AppointmentSlot.builder()
                .doctor(doctor).startsAt(start).endsAt(start.plus(30, ChronoUnit.MINUTES)).build());
        return appointmentRepository.save(Appointment.builder().slot(slot).patient(patient)
                .status(AppointmentStatus.BOOKED).reason("Check-up").scheduledAt(start).build());
    }

    private UUID closedQuestion(String status, int daysAgo) {
        UUID id = UUID.randomUUID();
        Instant created = clock.instant().minus(daysAgo + 1L, ChronoUnit.DAYS);
        jdbc.update("""
                INSERT INTO medicine_requests (id, patient_id, prescription_id, latitude, longitude, radius_m, status,
                                               stores_asked, created_at, expires_at, closed_at)
                VALUES (?, ?, ?, ?, ?, 3000, ?, 1, ?, ?, ?)""",
                id, meera.getId(), prescriptionId, LAT, LNG, status,
                Timestamp.from(created), Timestamp.from(created.plus(1, ChronoUnit.HOURS)),
                Timestamp.from(clock.instant().minus(daysAgo, ChronoUnit.DAYS)));
        return id;
    }

    private UUID openQuestion() {
        UUID id = UUID.randomUUID();
        Instant created = clock.instant().minus(40, ChronoUnit.DAYS);
        jdbc.update("""
                INSERT INTO medicine_requests (id, patient_id, prescription_id, latitude, longitude, radius_m, status,
                                               stores_asked, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, 3000, 'OPEN', 1, ?, ?)""",
                id, meera.getId(), prescriptionId, LAT, LNG,
                Timestamp.from(created), Timestamp.from(created.plus(1, ChronoUnit.HOURS)));
        return id;
    }

    /** "lat,lng" as stored (trailing zeros dropped), or null once cleared. */
    private String location(UUID question) {
        return jdbc.query("SELECT latitude, longitude FROM medicine_requests WHERE id = ?",
                (rs, i) -> rs.getBigDecimal("latitude") == null ? null
                        : rs.getBigDecimal("latitude").stripTrailingZeros().toPlainString() + ","
                        + rs.getBigDecimal("longitude").stripTrailingZeros().toPlainString(), question).get(0);
    }
}
