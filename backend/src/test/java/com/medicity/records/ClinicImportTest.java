package com.medicity.records;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicity.audit.AuditLog;
import com.medicity.doctor.Doctor;
import com.medicity.privacy.AccountDeletionService;
import com.medicity.privacy.DataExportService;
import com.medicity.request.NetworkTestSupport;
import com.medicity.security.AuthService;
import com.medicity.security.EmailOtpSender;
import com.medicity.security.OtpService;
import com.medicity.user.Role;
import com.medicity.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Importing a clinic's patient list: the checks, the two steps, re-imports, and who may see what. */
@DisplayName("Clinic records: CSV import and the patient's consent to link")
class ClinicImportTest extends NetworkTestSupport {

    private static final String BASE = "/api/v1/doctors/me/import/patients";
    private static final String HEADER = "Full name,Phone,Email,Date of birth,Gender,Blood group,Allergies,Notes";

    @Autowired ObjectMapper json;
    @Autowired AuthService auth;
    @Autowired AuditLog auditLog;
    @Autowired DataExportService exports;
    @Autowired AccountDeletionService deletion;
    @Autowired PasswordEncoder passwordEncoder;

    @Override
    protected void cleanUp() {
        jdbc.update("DELETE FROM clinic_records");
        super.cleanUp();
    }

    // --- helpers -------------------------------------------------------------------------

    private static byte[] file(String... lines) {
        return String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
    }

    private ResultActions upload(String step, User who, byte[] bytes, String... extraParams) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(BASE + "/" + step)
                .file(new MockMultipartFile("file", "patients.csv", "text/csv", bytes));
        for (int i = 0; i < extraParams.length; i += 2) {
            request.param(extraParams[i], extraParams[i + 1]);
        }
        return mvc.perform(request.header("Authorization", bearer(who)));
    }

    private JsonNode dryRun(byte[] bytes) throws Exception {
        return json.readTree(upload("dry-run", doctorUser, bytes).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode confirm(byte[] bytes) throws Exception {
        String sha = dryRun(bytes).get("fileSha256").asText();
        return json.readTree(upload("confirm", doctorUser, bytes, "fileSha256", sha).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private int records() {
        return jdbc.queryForObject("SELECT count(*) FROM clinic_records WHERE owner_id = ?", Integer.class, doctorUser.getId());
    }

    private Doctor secondDoctor() {
        User user = persistUser(Role.DOCTOR, "Dr. Second Clinic");
        return doctorRepository.save(Doctor.builder().user(user).specialization("Dermatology")
                .licenseNumber("KA-RQ-" + UUID.randomUUID().toString().substring(0, 8))
                .consultationFee(new BigDecimal("500.00")).yearsExperience(4).build());
    }

    // --- the template -----------------------------------------------------------------------

    @Test
    @DisplayName("the template is a CSV with a byte-order mark, the eight headings and no rows")
    void template() throws Exception {
        MvcResult result = mvc.perform(get(BASE + "/template").header("Authorization", bearer(doctorUser)))
                .andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentType()).startsWith("text/csv");
        assertThat(result.getResponse().getHeader("Content-Disposition")).contains("medicity-patient-import-template.csv");
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(body[0] & 0xFF).isEqualTo(0xEF);   // UTF-8 byte-order mark
        assertThat(new String(body, StandardCharsets.UTF_8).substring(1).trim()).isEqualTo(HEADER);
    }

    // --- the dry run ------------------------------------------------------------------------

    @Test
    @DisplayName("a dry run counts every row and names each problem, and writes nothing")
    void dryRunReportsAndWritesNothing() throws Exception {
        byte[] csv = file(HEADER,
                "Asha Verma,98765 43210,asha@example.com,12/08/1994,Female,O+,Penicillin,Prefers morning",     // row 2 good
                "Ravi Iyer,9876543211,,31/02/1990,Male,A+,,",                                                  // row 3 bad date
                "Sunil Das,12345,,01/01/1980,Male,,,",                                                         // row 4 bad phone
                "Asha V,+91 98765 43210,other@example.com,,,,,",                                               // row 5 same phone as row 2
                "Meena Rao,,meena@example.com,1975-03-04,F,AB-,,",                                             // row 6 good
                "Dup Mail,9000000001,ASHA@example.com,,,,,",                                                   // row 7 same email as row 2
                ",9000000002,,,,,,");                                                                          // row 8 no name

        JsonNode report = dryRun(csv);

        assertThat(report.get("applied").asBoolean()).isFalse();
        assertThat(report.get("totalRows").asInt()).isEqualTo(7);
        assertThat(report.get("validRows").asInt()).isEqualTo(2);
        assertThat(report.get("rejectedRows").asInt()).isEqualTo(5);
        assertThat(report.get("created").asInt()).isEqualTo(2);
        assertThat(report.get("updated").asInt()).isZero();
        assertThat(records()).isZero();

        List<String> problems = new ArrayList<>();
        report.get("errors").forEach(e -> problems.add(e.get("row").asInt() + ":" + e.get("code").asText()));
        assertThat(problems).containsExactlyInAnyOrder("3:DOB_INVALID", "4:PHONE_INVALID", "5:DUPLICATE_IN_FILE",
                "7:DUPLICATE_IN_FILE", "8:NAME_MISSING");
        // Messages say where and what, never the cell's content.
        assertThat(report.toString()).doesNotContain("Ravi").doesNotContain("Sunil").doesNotContain("98765");
    }

    @Test
    @DisplayName("semicolons, a byte-order mark and Excel's Windows-1252 encoding all read correctly")
    void excelVariants() throws Exception {
        String text = "Full name;Mobile;E-mail;DOB;Sex\r\nJosé D’Souza;9812345678;jose@example.com;5-6-1988;M\r\n;;;;\r\n";
        byte[] ansi = text.getBytes(Charset.forName("windows-1252"));
        JsonNode report = confirm(ansi);
        assertThat(report.get("created").asInt()).isEqualTo(1);
        assertThat(report.get("totalRows").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT full_name FROM clinic_records WHERE owner_id = ?", String.class,
                doctorUser.getId())).isEqualTo("José D’Souza");
        assertThat(jdbc.queryForObject("SELECT date_of_birth::text FROM clinic_records WHERE owner_id = ?", String.class,
                doctorUser.getId())).isEqualTo("1988-06-05");

        jdbc.update("DELETE FROM clinic_records");
        byte[] bomUtf8 = ("﻿" + "Name,Phone,Blood group\n\"Nair, Meera\",\"+91-98450-12345\",o positive\n")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(confirm(bomUtf8).get("created").asInt()).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("SELECT full_name, phone, blood_group FROM clinic_records WHERE owner_id = ?",
                doctorUser.getId());
        assertThat(row).containsEntry("full_name", "Nair, Meera").containsEntry("phone", "9845012345")
                .containsEntry("blood_group", "O+");

        jdbc.update("DELETE FROM clinic_records");
        byte[] utf16 = ("Full name\tPhone\nZoya Khan\t9811122233\n").getBytes(StandardCharsets.UTF_16);   // BOM, tab separated
        assertThat(confirm(utf16).get("created").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("an Excel number turned into 9.8E+11, and a binary workbook, are explained and refused")
    void excelTraps() throws Exception {
        JsonNode report = dryRun(file("Full name,Phone", "Asha Verma,9.87654E+11"));
        assertThat(report.get("errors").get(0).get("code").asText()).isEqualTo("PHONE_INVALID");
        assertThat(report.get("errors").get(0).get("message").asText()).contains("Excel");

        byte[] xlsx = new byte[]{'P', 'K', 3, 4, 0, 0, 0, 0};
        upload("dry-run", doctorUser, xlsx).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NOT_A_CSV"));

        upload("dry-run", doctorUser, file("Name,Notes", "Asha,x")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MISSING_COLUMN"));
        upload("dry-run", doctorUser, new byte[0]).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EMPTY_FILE"));
    }

    @Test
    @DisplayName("5,000 rows are accepted, 5,001 are refused, and a file over 2 MB is refused")
    void limits() throws Exception {
        StringBuilder ok = new StringBuilder("Full name,Phone\n");
        for (int i = 0; i < 5000; i++) {
            ok.append("Patient ").append(i).append(",9").append(String.format("%09d", i)).append('\n');
        }
        JsonNode report = dryRun(ok.toString().getBytes(StandardCharsets.UTF_8));
        assertThat(report.get("totalRows").asInt()).isEqualTo(5000);
        assertThat(report.get("created").asInt()).isEqualTo(5000);

        ok.append("Patient extra,9999999999\n");
        upload("dry-run", doctorUser, ok.toString().getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TOO_MANY_ROWS"));

        StringBuilder big = new StringBuilder("Full name,Phone,Notes\nAsha Verma,9876543210,");
        big.append("x".repeat(2 * 1024 * 1024 + 10)).append('\n');
        upload("dry-run", doctorUser, big.toString().getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
        assertThat(records()).isZero();
    }

    // --- confirm and re-import ---------------------------------------------------------------

    @Test
    @DisplayName("confirm saves the good rows as clinic records owned by the doctor, never as accounts")
    void confirmSavesRecords() throws Exception {
        int accountsBefore = jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);
        int patientsBefore = jdbc.queryForObject("SELECT count(*) FROM patients", Integer.class);
        byte[] csv = file(HEADER,
                "Asha Verma,9876543210,asha@example.com,12/08/1994,Female,O+,Penicillin,Prefers mornings",
                "Bad Row,123,,,,,,");

        JsonNode report = confirm(csv);

        assertThat(report.get("applied").asBoolean()).isTrue();
        assertThat(report.get("created").asInt()).isEqualTo(1);
        assertThat(report.get("rejectedRows").asInt()).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM clinic_records WHERE owner_id = ?", doctorUser.getId());
        assertThat(row).containsEntry("owner_kind", "DOCTOR").containsEntry("full_name", "Asha Verma")
                .containsEntry("phone", "9876543210").containsEntry("email", "asha@example.com")
                .containsEntry("gender", "FEMALE").containsEntry("blood_group", "O+")
                .containsEntry("allergies", "Penicillin").containsEntry("notes", "Prefers mornings")
                .containsEntry("link_state", "UNLINKED");
        assertThat(row.get("date_of_birth").toString()).isEqualTo("1994-08-12");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(accountsBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patients", Integer.class)).isEqualTo(patientsBefore);
    }

    @Test
    @DisplayName("importing the same file again changes nothing; a corrected cell updates the record, not a copy")
    void reImportIsIdempotent() throws Exception {
        byte[] first = file(HEADER,
                "Asha Verma,9876543210,asha@example.com,12/08/1994,Female,O+,,Old note",
                "Meena Rao,,meena@example.com,,,,,");
        assertThat(confirm(first).get("created").asInt()).isEqualTo(2);

        JsonNode again = confirm(first);
        assertThat(again.get("created").asInt()).isZero();
        assertThat(again.get("updated").asInt()).isZero();
        assertThat(again.get("unchanged").asInt()).isEqualTo(2);
        assertThat(records()).isEqualTo(2);

        // Same person found by phone written another way; new note; empty cells leave the old values alone.
        byte[] corrected = file(HEADER,
                "Asha Verma,+91 98765-43210,,,,,,New note",
                "Meena Rao,,MEENA@example.com,,,,Dust,");
        JsonNode third = confirm(corrected);
        assertThat(third.get("created").asInt()).isZero();
        assertThat(third.get("updated").asInt()).isEqualTo(2);
        assertThat(records()).isEqualTo(2);
        Map<String, Object> asha = jdbc.queryForMap("SELECT * FROM clinic_records WHERE phone = '9876543210' AND owner_id = ?",
                doctorUser.getId());
        assertThat(asha).containsEntry("notes", "New note").containsEntry("email", "asha@example.com");
        assertThat(asha.get("date_of_birth").toString()).isEqualTo("1994-08-12");
    }

    @Test
    @DisplayName("a row whose email is one saved patient and phone another is refused, not merged")
    void ambiguousMatch() throws Exception {
        confirm(file("Full name,Phone,Email", "Asha,9876543210,asha@example.com", "Meena,9876543211,meena@example.com"));

        JsonNode report = confirm(file("Full name,Phone,Email", "Mix Up,9876543210,meena@example.com"));
        assertThat(report.get("rejectedRows").asInt()).isEqualTo(1);
        assertThat(report.get("errors").get(0).get("code").asText()).isEqualTo("AMBIGUOUS_MATCH");
        assertThat(records()).isEqualTo(2);
    }

    @Test
    @DisplayName("two doctors importing the same phone each keep their own record")
    void ownersAreSeparate() throws Exception {
        confirm(file("Full name,Phone", "Asha Verma,9876543210"));
        Doctor other = secondDoctor();
        byte[] csv = file("Full name,Phone", "Asha Verma,9876543210");
        String sha = json.readTree(upload("dry-run", other.getUser(), csv).andReturn().getResponse().getContentAsString())
                .get("fileSha256").asText();
        upload("confirm", other.getUser(), csv, "fileSha256", sha).andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM clinic_records WHERE phone = '9876543210'", Integer.class))
                .isEqualTo(2);
        assertThat(records()).isEqualTo(1);
    }

    @Test
    @DisplayName("confirm needs the checked file's hash; a different file is a 409 and saves nothing")
    void confirmNeedsTheCheckedFile() throws Exception {
        byte[] checked = file("Full name,Phone", "Asha Verma,9876543210");
        String sha = dryRun(checked).get("fileSha256").asText();
        byte[] swapped = file("Full name,Phone", "Someone Else,9876543299");

        upload("confirm", doctorUser, swapped, "fileSha256", sha).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FILE_CHANGED"));
        upload("confirm", doctorUser, checked, "fileSha256", "0".repeat(64)).andExpect(status().isConflict());
        assertThat(records()).isZero();
    }

    @Test
    @DisplayName("only a verified doctor can import; patients and other roles are refused")
    void whoMayImport() throws Exception {
        byte[] csv = file("Full name,Phone", "Asha Verma,9876543210");
        upload("dry-run", meeraUser, csv).andExpect(status().isForbidden());
        upload("dry-run", nearOwner, csv).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/template")).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/template").header("Authorization", bearer(meeraUser))).andExpect(status().isForbidden());

        jdbc.update("UPDATE doctors SET verified_at = NULL WHERE user_id = ?", doctorUser.getId());
        upload("dry-run", doctorUser, csv).andExpect(status().isForbidden());
        assertThat(records()).isZero();
    }

    // --- the error report ------------------------------------------------------------------------

    @Test
    @DisplayName("the error report lists each problem with its row, and does not let a cell run as a formula")
    void errorReport() throws Exception {
        byte[] csv = file("Full name,Phone,Date of birth",
                "Asha Verma,9876543210,12/08/1994",
                "Zed Kumar,9876543211,=1+1");
        MvcResult result = upload("error-report", doctorUser, csv).andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getContentType()).startsWith("text/csv");
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿Row,Column,Problem,Value in that cell");
        assertThat(body).contains("3,\"Date of birth\"").contains("\"'=1+1\"");
        assertThat(body).doesNotContain("Asha Verma");
    }

    // --- what is logged and audited -------------------------------------------------------------------

    @Test
    @DisplayName("the log and the audit trail get counts, never what is in a row")
    void noRowContentsInLogsOrAudit() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            confirm(file("Full name,Phone,Email,Notes", "Zaphod Beeblebrox,9876500001,zaphod@example.com,secretnote",
                    "Bad Person Zed,12,,"));
            upload("error-report", doctorUser, file("Full name,Phone", "Bad Person Zed,12")).andExpect(status().isOk());
        } finally {
            root.detachAppender(appender);
        }

        for (ILoggingEvent e : appender.list) {
            String line = e.getFormattedMessage() + (e.getThrowableProxy() == null ? "" : e.getThrowableProxy().getMessage());
            assertThat(line).doesNotContain("Zaphod").doesNotContain("9876500001").doesNotContain("secretnote")
                    .doesNotContain("Bad Person");
        }
        List<String> details = jdbc.queryForList(
                "SELECT detail::text FROM audit_log WHERE action LIKE 'CLINIC_IMPORT%' AND actor_id = ? ORDER BY occurred_at",
                String.class, doctorUser.getId());
        assertThat(details).isNotEmpty();
        assertThat(String.join(" ", details)).doesNotContain("Zaphod").doesNotContain("zaphod").doesNotContain("9876500001");
        Map<String, Object> done = jdbc.queryForMap(
                "SELECT detail::text AS d, entity_type FROM audit_log WHERE action = 'CLINIC_IMPORT' AND actor_id = ?",
                doctorUser.getId());
        JsonNode counts = json.readTree((String) done.get("d"));
        assertThat(counts.get("created").asInt()).isEqualTo(1);
        assertThat(counts.get("rejected").asInt()).isEqualTo(1);
    }

    // --- the patient's consent to link ------------------------------------------------------------------

    /** The OTP service with a sender that keeps the code, as the existing sign-in tests do. */
    private static final class Inbox implements EmailOtpSender {
        String code;
        @Override public boolean available() { return true; }
        @Override public void send(String address, String c) { code = c; }
    }

    private void verifyEmail(User user) {
        Inbox inbox = new Inbox();
        OtpService otp = new OtpService(userRepository, jdbc, auth, auditLog, inbox, Runnable::run, clock, false,
                "test-secret-not-the-real-one");
        otp.sendToEmail(user.getEmail());
        // The real service hashes with the server secret; this one shares only its own test secret, so the
        // verification goes through the same instance.
        otp.confirmEmail(user.getId(), inbox.code);
    }

    @Test
    @DisplayName("an unverified email is shown nothing, and cannot accept, even when a clinic holds a record for it")
    void unverifiedSeesNothing() throws Exception {
        confirm(file("Full name,Phone,Email,Notes", "Meera Nair,9876543210," + meeraUser.getEmail() + ",Clinic-only note"));
        UUID record = jdbc.queryForObject("SELECT id FROM clinic_records WHERE owner_id = ?", UUID.class, doctorUser.getId());

        mvc.perform(get("/api/v1/patients/me/clinic-records").header("Authorization", bearer(meeraUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerified").value(false))
                .andExpect(jsonPath("$.offers").isEmpty())
                .andExpect(jsonPath("$.linked").isEmpty());
        mvc.perform(post("/api/v1/patients/me/clinic-records/" + record + "/accept").header("Authorization", bearer(meeraUser)))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT link_state FROM clinic_records WHERE id = ?", String.class, record))
                .isEqualTo("UNLINKED");
        // Not even a doctor can read the record back through the patient route.
        mvc.perform(get("/api/v1/patients/me/clinic-records").header("Authorization", bearer(doctorUser)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("after verifying the address the patient is offered the clinic, sees no notes until yes, then sees them")
    void offerThenAccept() throws Exception {
        confirm(file("Full name,Phone,Email,Notes", "Meera Nair,9876543210," + meeraUser.getEmail() + ",Clinic-only note",
                "Someone Else,9876543211,somebody-else@example.com,Not for Meera"));
        UUID record = jdbc.queryForObject("SELECT id FROM clinic_records WHERE owner_id = ? AND email = ?", UUID.class,
                doctorUser.getId(), meeraUser.getEmail());
        verifyEmail(meeraUser);

        String offered = mvc.perform(get("/api/v1/patients/me/clinic-records").header("Authorization", bearer(meeraUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.offers.length()").value(1))
                .andExpect(jsonPath("$.offers[0].clinicName").value("Dr. Anjali Rao"))
                .andExpect(jsonPath("$.linked").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(offered).doesNotContain("Clinic-only note").doesNotContain("9876543210");
        assertThat(jdbc.queryForObject("SELECT link_state FROM clinic_records WHERE id = ?", String.class, record))
                .isEqualTo("UNLINKED");   // verifying is not consenting

        // Someone else's record cannot be accepted by guessing its id.
        UUID others = jdbc.queryForObject("SELECT id FROM clinic_records WHERE email = 'somebody-else@example.com'", UUID.class);
        mvc.perform(post("/api/v1/patients/me/clinic-records/" + others + "/accept").header("Authorization", bearer(meeraUser)))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/patients/me/clinic-records/" + record + "/accept").header("Authorization", bearer(meeraUser)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/patients/me/clinic-records").header("Authorization", bearer(meeraUser)))
                .andExpect(jsonPath("$.offers").isEmpty())
                .andExpect(jsonPath("$.linked.length()").value(1))
                .andExpect(jsonPath("$.linked[0].notes").value("Clinic-only note"))
                .andExpect(jsonPath("$.linked[0].clinicName").value("Dr. Anjali Rao"));
        Map<String, Object> row = jdbc.queryForMap("SELECT link_state, linked_user_id, linked_patient_id FROM clinic_records WHERE id = ?",
                record);
        assertThat(row).containsEntry("link_state", "ACCEPTED").containsEntry("linked_user_id", meeraUser.getId())
                .containsEntry("linked_patient_id", meera.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'CLINIC_RECORD_ACCEPTED' AND actor_id = ?",
                Integer.class, meeraUser.getId())).isEqualTo(1);
        // A second yes is not a second link.
        mvc.perform(post("/api/v1/patients/me/clinic-records/" + record + "/accept").header("Authorization", bearer(meeraUser)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("saying no keeps the record hidden and unlinked, and it is not offered again after a re-import")
    void decline() throws Exception {
        byte[] csv = file("Full name,Phone,Email,Notes", "Meera Nair,9876543210," + meeraUser.getEmail() + ",Clinic-only note");
        confirm(csv);
        UUID record = jdbc.queryForObject("SELECT id FROM clinic_records WHERE owner_id = ?", UUID.class, doctorUser.getId());
        verifyEmail(meeraUser);

        mvc.perform(post("/api/v1/patients/me/clinic-records/" + record + "/decline").header("Authorization", bearer(meeraUser)))
                .andExpect(status().isNoContent());
        confirm(file("Full name,Phone,Email,Notes", "Meera Nair,9876543210," + meeraUser.getEmail() + ",Changed note"));

        mvc.perform(get("/api/v1/patients/me/clinic-records").header("Authorization", bearer(meeraUser)))
                .andExpect(jsonPath("$.offers").isEmpty()).andExpect(jsonPath("$.linked").isEmpty());
        Map<String, Object> row = jdbc.queryForMap("SELECT link_state, linked_user_id, notes FROM clinic_records WHERE id = ?", record);
        assertThat(row).containsEntry("link_state", "DECLINED").containsEntry("notes", "Changed note");
        assertThat(row.get("linked_user_id")).isNull();
    }

    @Test
    @DisplayName("a phone number alone never links a record: the account's phone matches, but no offer is made")
    void phoneNeverLinks() throws Exception {
        jdbc.update("UPDATE users SET phone = '9876543210' WHERE id = ?", meeraUser.getId());
        confirm(file("Full name,Phone", "Meera Nair,9876543210"));
        verifyEmail(meeraUser);

        mvc.perform(get("/api/v1/patients/me/clinic-records").header("Authorization", bearer(meeraUser)))
                .andExpect(jsonPath("$.emailVerified").value(true)).andExpect(jsonPath("$.offers").isEmpty());
    }

    @Test
    @DisplayName("asking for the code through the API without an email provider says so, and verifies nothing")
    void codeEndpoints() throws Exception {
        mvc.perform(post("/api/v1/patients/me/clinic-records/email-code").header("Authorization", bearer(meeraUser)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.delivery").value("UNAVAILABLE"));
        mvc.perform(post("/api/v1/patients/me/clinic-records/email-code/verify").header("Authorization", bearer(meeraUser))
                        .contentType("application/json").content("{\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("WRONG_CODE"));
        assertThat(jdbc.queryForObject("SELECT email_verified_at FROM users WHERE id = ?", Object.class, meeraUser.getId())).isNull();
    }

    // --- privacy: export and deletion ------------------------------------------------------------------------

    @Test
    @DisplayName("the data export holds the records the person accepted and none they did not")
    void exportIncludesAcceptedOnly() throws Exception {
        confirm(file("Full name,Phone,Email,Notes", "Meera Nair,9876543210," + meeraUser.getEmail() + ",Clinic-only note"));
        Map<String, Object> before = exports.export(meeraUser.getId());
        assertThat((List<?>) before.get("clinicRecords")).isEmpty();

        UUID record = jdbc.queryForObject("SELECT id FROM clinic_records WHERE owner_id = ?", UUID.class, doctorUser.getId());
        verifyEmail(meeraUser);
        mvc.perform(post("/api/v1/patients/me/clinic-records/" + record + "/accept").header("Authorization", bearer(meeraUser)))
                .andExpect(status().isNoContent());

        List<Map<String, Object>> after = castRows(exports.export(meeraUser.getId()).get("clinicRecords"));
        assertThat(after).hasSize(1);
        assertThat(after.get(0)).containsEntry("notes", "Clinic-only note").containsEntry("clinicName", "Dr. Anjali Rao")
                .doesNotContainKeys("ownerId", "ownerKind", "linkedUserId");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castRows(Object o) {
        return (List<Map<String, Object>>) o;
    }

    @Test
    @DisplayName("deleting the account ends the link and keeps the clinic's record, never offered again")
    void deletionUnlinks() throws Exception {
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", passwordEncoder.encode("meera-password-2026"), meeraUser.getId());
        confirm(file("Full name,Phone,Email,Notes", "Meera Nair,9876543210," + meeraUser.getEmail() + ",Clinic-only note"));
        UUID record = jdbc.queryForObject("SELECT id FROM clinic_records WHERE owner_id = ?", UUID.class, doctorUser.getId());
        verifyEmail(meeraUser);
        mvc.perform(post("/api/v1/patients/me/clinic-records/" + record + "/accept").header("Authorization", bearer(meeraUser)))
                .andExpect(status().isNoContent());

        Map<String, Integer> counts = deletion.delete(meeraUser.getId(), "meera-password-2026");

        assertThat(counts).containsEntry("clinicRecordsUnlinked", 1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT link_state, linked_user_id, linked_patient_id, notes FROM clinic_records WHERE id = ?", record);
        assertThat(row).containsEntry("link_state", "DECLINED").containsEntry("notes", "Clinic-only note");
        assertThat(row.get("linked_user_id")).isNull();
        assertThat(row.get("linked_patient_id")).isNull();
    }
}
