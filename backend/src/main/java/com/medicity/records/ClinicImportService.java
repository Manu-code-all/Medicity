package com.medicity.records;

import com.medicity.audit.AuditLog;
import com.medicity.common.ConflictException;
import com.medicity.common.ForbiddenException;
import com.medicity.records.PatientCsv.Field;
import com.medicity.records.PatientCsv.Issue;
import com.medicity.records.PatientCsv.Parsed;
import com.medicity.records.PatientCsv.Row;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Brings a clinic's old patient list in from a CSV, in two steps.
 *
 * <ol>
 *   <li>{@link #preview}: reads and checks every row and says what an import
 *       would do. Writes nothing.</li>
 *   <li>{@link #confirm}: the same checks again on the same file (the file's
 *       hash must be the one that was previewed), then writes the good rows
 *       and skips the rest.</li>
 * </ol>
 *
 * <p><b>Owned by a doctor account for now.</b> Rows are stored with an
 * {@code owner_kind} and {@code owner_id}; a clinic can become an owner later
 * without moving anything (V48).
 *
 * <p><b>Re-importing the same list changes nothing.</b> A row whose email or
 * phone is already one of the owner's records updates that record (only the
 * cells that have something in them) instead of adding a second one. A row
 * whose email belongs to one record and whose phone to another is refused,
 * not guessed at.
 *
 * <p><b>Row contents are never logged.</b> The log and the audit trail get
 * counts. Error messages name the row and column, not the value.
 */
@Service
@Slf4j
public class ClinicImportService {

    static final String OWNER_KIND = "DOCTOR";

    /** How many problems the preview lists; the count is always complete and the error report has all of them. */
    static final int LISTED_ISSUES = 100;

    private final JdbcTemplate jdbc;
    private final AuditLog auditLog;
    private final Clock clock;
    private final PatientCsv csv;

    public ClinicImportService(JdbcTemplate jdbc, AuditLog auditLog, Clock clock) {
        this.jdbc = jdbc;
        this.auditLog = auditLog;
        this.clock = clock;
        this.csv = new PatientCsv(clock);
    }

    /** What an import would do ({@code applied} false) or did ({@code applied} true). */
    public record Report(boolean applied, String fileSha256, int totalRows, int validRows, int rejectedRows,
                         int created, int updated, int unchanged, int errorCount, List<IssueView> errors,
                         boolean errorsTruncated, List<String> ignoredColumns) {}

    public record IssueView(int row, String column, String code, String message) {}

    /** An existing record the file's row refers to. */
    private record Existing(UUID id, String fullName, String phone, String email, java.time.LocalDate dateOfBirth,
                            String gender, String bloodGroup, String allergies, String notes, String linkState) {}

    private record Update(Existing before, Row row) {}

    private record Plan(Parsed parsed, List<Row> create, List<Update> update, int unchanged, List<Issue> issues) {
        int rejectedRows() {
            return (int) issues.stream().mapToInt(Issue::row).distinct().count();
        }
    }

    // --- the two steps -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public Report preview(UUID owner, byte[] file) {
        requireVerifiedDoctor(owner);
        Plan plan = plan(owner, file);
        Report report = report(false, file, plan, plan.create.size(), plan.update.size());
        auditLog.recordIndependently("CLINIC_IMPORT_PREVIEWED", "CLINIC_RECORDS", owner, AuditLog.Outcome.SUCCESS,
                counts(report));
        return report;
    }

    /**
     * @param expectedSha256 the {@code fileSha256} the preview returned: the file imported is the file looked at
     */
    @Transactional
    public Report confirm(UUID owner, byte[] file, String expectedSha256) {
        requireVerifiedDoctor(owner);
        if (expectedSha256 == null || !expectedSha256.equalsIgnoreCase(sha256(file))) {
            throw new ConflictException("FILE_CHANGED",
                    "This is not the file that was checked. Choose the file again and check it before importing.");
        }
        // One import per owner at a time: two at once could both decide "new" for the same phone.
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(?))) locked", Integer.class,
                "medicity.clinic-import." + owner);

        Plan plan = plan(owner, file);
        Timestamp now = Timestamp.from(clock.instant());

        jdbc.batchUpdate("""
                INSERT INTO clinic_records (owner_kind, owner_id, full_name, phone, email, date_of_birth, gender,
                                            blood_group, allergies, notes, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", plan.create, 500, (ps, r) -> {
            ps.setString(1, OWNER_KIND);
            ps.setObject(2, owner);
            ps.setString(3, r.fullName());
            ps.setString(4, r.phone());
            ps.setString(5, r.email());
            ps.setDate(6, r.dateOfBirth() == null ? null : Date.valueOf(r.dateOfBirth()));
            ps.setString(7, r.gender());
            ps.setString(8, r.bloodGroup());
            ps.setString(9, r.allergies());
            ps.setString(10, r.notes());
            ps.setTimestamp(11, now);
            ps.setTimestamp(12, now);
        });

        jdbc.batchUpdate("""
                UPDATE clinic_records SET full_name = ?, phone = ?, email = ?, date_of_birth = ?, gender = ?,
                       blood_group = ?, allergies = ?, notes = ?, link_state = ?, updated_at = ?
                WHERE id = ? AND owner_kind = ? AND owner_id = ?""", plan.update, 500, (ps, u) -> {
            Existing b = u.before;
            Row r = u.row;
            String email = firstNonNull(r.email(), b.email());
            ps.setString(1, r.fullName());
            ps.setString(2, firstNonNull(r.phone(), b.phone()));
            ps.setString(3, email);
            ps.setObject(4, firstNonNull(r.dateOfBirth(), b.dateOfBirth()));
            ps.setString(5, firstNonNull(r.gender(), b.gender()));
            ps.setString(6, firstNonNull(r.bloodGroup(), b.bloodGroup()));
            ps.setString(7, firstNonNull(r.allergies(), b.allergies()));
            ps.setString(8, firstNonNull(r.notes(), b.notes()));
            // A "no thanks" was said about one email address; a different one is a different question.
            ps.setString(9, "DECLINED".equals(b.linkState) && !Objects.equals(email, b.email) ? "UNLINKED" : b.linkState);
            ps.setTimestamp(10, now);
            ps.setObject(11, b.id);
            ps.setString(12, OWNER_KIND);
            ps.setObject(13, owner);
        });

        Report report = report(true, file, plan, plan.create.size(), plan.update.size());
        auditLog.recordChange("CLINIC_IMPORT", "CLINIC_RECORDS", owner, counts(report));
        log.info("Clinic import for user {}: {} rows, {} created, {} updated, {} unchanged, {} rows rejected",
                owner, report.totalRows, report.created, report.updated, report.unchanged, report.rejectedRows);
        return report;
    }

    /** Every problem in the file as a CSV the doctor can open in Excel; empty body of rows when there are none. */
    @Transactional(readOnly = true)
    public byte[] errorReport(UUID owner, byte[] file) {
        requireVerifiedDoctor(owner);
        Plan plan = plan(owner, file);
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("Row,Column,Problem,Value in that cell\r\n");
        for (Issue i : plan.issues) {
            String value = "";
            Map<Field, String> cells = plan.parsed.cells().get(i.row());
            if (cells != null && i.field() != null) {
                for (Field f : Field.values()) {
                    if (f.label.equals(i.field())) {
                        value = cells.getOrDefault(f, "");
                    }
                }
            }
            sb.append(i.row()).append(',')
                    .append(cell(i.field() == null ? "" : i.field())).append(',')
                    .append(cell(i.message())).append(',')
                    .append(cell(value.length() > 80 ? value.substring(0, 80) : value)).append("\r\n");
        }
        auditLog.recordIndependently("CLINIC_IMPORT_ERROR_REPORT", "CLINIC_RECORDS", owner, AuditLog.Outcome.SUCCESS,
                Map.of("errorCount", plan.issues.size()));
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The empty file to fill in. A byte-order mark so Excel opens the accents and Indian scripts correctly. */
    public static byte[] template() {
        return ("﻿" + String.join(",", PatientCsv.TEMPLATE_HEADINGS) + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    // --- planning -----------------------------------------------------------------------

    private Plan plan(UUID owner, byte[] file) {
        Parsed parsed = csv.parse(file);
        List<Issue> issues = new ArrayList<>(parsed.issues());

        List<String> emails = parsed.valid().stream().map(Row::email).filter(Objects::nonNull).toList();
        List<String> phones = parsed.valid().stream().map(Row::phone).filter(Objects::nonNull).toList();
        Map<String, Existing> byEmail = new HashMap<>();
        Map<String, Existing> byPhone = new HashMap<>();
        if (!emails.isEmpty() || !phones.isEmpty()) {
            List<Existing> found = jdbc.query(con -> {
                var ps = con.prepareStatement("""
                        SELECT id, full_name, phone, email, date_of_birth, gender, blood_group, allergies, notes, link_state
                        FROM clinic_records
                        WHERE owner_kind = ? AND owner_id = ? AND (email = ANY (?) OR phone = ANY (?))""");
                ps.setString(1, OWNER_KIND);
                ps.setObject(2, owner);
                ps.setArray(3, con.createArrayOf("text", emails.toArray()));
                ps.setArray(4, con.createArrayOf("text", phones.toArray()));
                return ps;
            }, (rs, n) -> new Existing(rs.getObject("id", UUID.class), rs.getString("full_name"),
                    rs.getString("phone"), rs.getString("email"),
                    rs.getObject("date_of_birth", java.time.LocalDate.class), rs.getString("gender"),
                    rs.getString("blood_group"), rs.getString("allergies"), rs.getString("notes"),
                    rs.getString("link_state")));
            for (Existing e : found) {
                if (e.email != null) byEmail.put(e.email, e);
                if (e.phone != null) byPhone.put(e.phone, e);
            }
        }

        List<Row> create = new ArrayList<>();
        List<Update> update = new ArrayList<>();
        int unchanged = 0;
        Map<UUID, Integer> claimed = new LinkedHashMap<>();   // existing record -> the row that refers to it
        for (Row r : parsed.valid()) {
            Existing a = r.email() == null ? null : byEmail.get(r.email());
            Existing b = r.phone() == null ? null : byPhone.get(r.phone());
            if (a != null && b != null && !a.id.equals(b.id)) {
                issues.add(new Issue(r.row(), null, "AMBIGUOUS_MATCH",
                        "The email is one saved patient and the phone another. Fix one of them in the file."));
                continue;
            }
            Existing match = a != null ? a : b;
            if (match == null) {
                create.add(r);
                continue;
            }
            Integer earlier = claimed.putIfAbsent(match.id, r.row());
            if (earlier != null) {
                issues.add(new Issue(r.row(), null, "DUPLICATE_IN_FILE",
                        "Same patient as row " + earlier + " (they share a phone or email already saved)."));
                continue;
            }
            if (changes(match, r)) {
                update.add(new Update(match, r));
            } else {
                unchanged++;
            }
        }
        issues.sort(java.util.Comparator.comparingInt(Issue::row));
        return new Plan(parsed, create, update, unchanged, issues);
    }

    /** True when the row would alter the saved record: the cells with something in them differ. */
    private static boolean changes(Existing b, Row r) {
        return !Objects.equals(b.fullName, r.fullName())
                || differs(r.phone(), b.phone) || differs(r.email(), b.email)
                || differs(r.dateOfBirth(), b.dateOfBirth) || differs(r.gender(), b.gender)
                || differs(r.bloodGroup(), b.bloodGroup) || differs(r.allergies(), b.allergies)
                || differs(r.notes(), b.notes);
    }

    private static boolean differs(Object incoming, Object saved) {
        return incoming != null && !incoming.equals(saved);
    }

    private static <T> T firstNonNull(T a, T b) {
        return a != null ? a : b;
    }

    // --- reporting ------------------------------------------------------------------------

    private Report report(boolean applied, byte[] file, Plan plan, int created, int updated) {
        List<IssueView> listed = plan.issues.stream().limit(LISTED_ISSUES)
                .map(i -> new IssueView(i.row(), i.field(), i.code(), i.message())).toList();
        int rejected = plan.rejectedRows();
        return new Report(applied, sha256(file), plan.parsed.totalRows(), plan.parsed.totalRows() - rejected,
                rejected, created, updated, plan.unchanged, plan.issues.size(), listed,
                plan.issues.size() > LISTED_ISSUES, plan.parsed.ignoredColumns());
    }

    /** Counts only: this is what the audit trail keeps. */
    private static Map<String, Object> counts(Report r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", r.totalRows);
        m.put("created", r.created);
        m.put("updated", r.updated);
        m.put("unchanged", r.unchanged);
        m.put("rejected", r.rejectedRows);
        return m;
    }

    /** A CSV cell: quoted, and a leading = + - @ made harmless so Excel does not run it as a formula. */
    static String cell(String s) {
        String t = s == null ? "" : s;
        if (!t.isEmpty() && "=+-@\t\r".indexOf(t.charAt(0)) >= 0) {
            t = "'" + t;
        }
        return "\"" + t.replace("\"", "\"\"") + "\"";
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Importing patient details is for a doctor whose registration has been checked. */
    private void requireVerifiedDoctor(UUID userId) {
        List<Timestamp> verified = jdbc.query("SELECT verified_at FROM doctors WHERE user_id = ?",
                (rs, n) -> rs.getTimestamp(1), userId);
        if (verified.isEmpty() || verified.get(0) == null) {
            throw new ForbiddenException("You can import patients once your registration has been verified.");
        }
    }
}
