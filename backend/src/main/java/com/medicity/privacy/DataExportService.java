package com.medicity.privacy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicity.audit.AuditLog;
import com.medicity.common.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Give me a copy of my data" (DPDP Act right of access): everything Medicity
 * holds about an account holder and the family members they manage, as one
 * JSON document.
 *
 * <p>Plain SQL rather than the JPA entities: the export is a list of what is
 * stored, field by field, and must not silently omit a column because a
 * response record did not mention it. Three things are left out on purpose:
 * password hashes and session tokens (not the person's data, and a download
 * is a place secrets leak from); pickup codes of reservations (a live secret
 * for the chemist counter); and the bytes of uploaded files (their names,
 * types and sizes are listed; the files are in the visit they were attached to).
 *
 * <p>Keys are the database's column names in camelCase.
 */
@Service
@RequiredArgsConstructor
public class DataExportService {

    /** The account's patients: the holder and their family. Two bind parameters (user id twice). */
    private static final String MINE = "(SELECT id FROM patients WHERE user_id = ? OR guardian_user_id = ?)";

    /** The latest rows of the account's own activity, so the export stays a size a browser can hold. */
    private static final int ACTIVITY_LIMIT = 1000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditLog auditLog;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Map<String, Object> export(UUID userId) {
        List<Map<String, Object>> account = rows("""
                SELECT id, email, full_name, phone, login_phone, role, created_at
                FROM users WHERE id = ? AND deleted_at IS NULL""", userId);
        if (account.isEmpty()) {
            throw new NotFoundException("Account", userId);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exportedAt", clock.instant());
        out.put("about", "Everything Medicity holds about this account and the family members it manages. "
                + "Passwords, sign-in sessions, pickup codes and the contents of uploaded files are not included.");
        out.put("account", account.get(0));
        out.put("people", rows("""
                SELECT id, full_name, relationship, guardian_user_id, date_of_birth, gender, blood_group,
                       address_line, city, emergency_contact, height_cm, weight_kg, allergies,
                       chronic_conditions, current_medications, home_latitude, home_longitude, created_at
                FROM patients WHERE user_id = ? OR guardian_user_id = ? ORDER BY created_at""", userId, userId));
        out.put("appointments", rows("""
                SELECT a.id, a.patient_id, a.status, a.reason, a.scheduled_at, s.ends_at, a.visit_type,
                       a.intake_summary, a.cancelled_at, a.cancel_reason, a.rescheduled_from, a.created_at,
                       du.full_name AS doctor_name, d.specialization
                FROM appointments a
                JOIN appointment_slots s ON s.id = a.slot_id
                JOIN doctors d ON d.id = s.doctor_id
                JOIN users du ON du.id = d.user_id
                WHERE a.patient_id IN %s ORDER BY a.scheduled_at""".formatted(MINE), userId, userId));
        out.put("prescriptions", prescriptions(userId));
        out.put("visitMessages", rows("""
                SELECT f.id, f.appointment_id, f.sender, f.body, f.created_at
                FROM visit_followups f JOIN appointments a ON a.id = f.appointment_id
                WHERE a.patient_id IN %s ORDER BY f.seq""".formatted(MINE), userId, userId));
        out.put("uploadedFiles", rows("""
                SELECT t.id, t.appointment_id, t.file_name, t.content_type, t.size_bytes, t.note, t.uploaded_at
                FROM appointment_attachments t JOIN appointments a ON a.id = t.appointment_id
                WHERE a.patient_id IN %s ORDER BY t.uploaded_at""".formatted(MINE), userId, userId));
        out.put("medicineQuestions", rows("""
                SELECT r.id, r.patient_id, r.prescription_id, r.latitude, r.longitude, r.radius_m, r.status,
                       r.stores_asked, r.created_at, r.closed_at
                FROM medicine_requests r WHERE r.patient_id IN %s ORDER BY r.created_at""".formatted(MINE), userId, userId));
        out.put("reservations", rows("""
                SELECT x.id, x.request_id, x.patient_id, st.name AS store_name, x.status, x.total, x.complete,
                       x.created_at, x.expires_at, x.collected_at
                FROM reservations x JOIN stores st ON st.id = x.store_id
                WHERE x.patient_id IN %s ORDER BY x.created_at""".formatted(MINE), userId, userId));
        out.put("reviews", rows("""
                SELECT id, appointment_id, doctor_id, patient_id, rating, comment, created_at
                FROM doctor_reviews WHERE patient_id IN %s ORDER BY created_at""".formatted(MINE), userId, userId));
        out.put("walkInPlaces", rows("""
                SELECT id, patient_id, doctor_id, queue_date, token_no, status, reason, joined_at, called_at, finished_at
                FROM queue_tokens WHERE patient_id IN %s ORDER BY joined_at""".formatted(MINE), userId, userId));
        out.put("waitingList", rows("""
                SELECT id, patient_id, doctor_id, target_date, status, created_at, notified_at
                FROM slot_waitlist WHERE patient_id IN %s ORDER BY created_at""".formatted(MINE), userId, userId));
        out.put("notifications", rows("""
                SELECT id, kind, title, body, link, occurs_at, created_at, read_at
                FROM notifications WHERE user_id = ? ORDER BY created_at""", userId));
        out.put("consents", rows("""
                SELECT consent_type, policy_version, capacity, source, subject_patient_id, given_at
                FROM consent_records WHERE user_id = ? ORDER BY given_at, consent_type""", userId));
        out.put("recentActivity", rows("""
                SELECT action, entity_type, entity_id, outcome, occurred_at
                FROM audit_log WHERE actor_id = ? ORDER BY occurred_at DESC LIMIT %d""".formatted(ACTIVITY_LIMIT), userId));

        auditLog.recordIndependently("DATA_EXPORTED", "USER", userId, AuditLog.Outcome.SUCCESS, null);
        return out;
    }

    /** Each prescription with its medicines nested, and when the pharmacy filled it. */
    private List<Map<String, Object>> prescriptions(UUID userId) {
        List<Map<String, Object>> prescriptions = rows("""
                SELECT p.id, p.appointment_id, p.patient_id, p.diagnosis, p.diagnosis_code, p.notes,
                       p.supersedes_id, p.issued_at, du.full_name AS doctor_name, d.specialization,
                       pd.dispensed_at
                FROM prescriptions p
                JOIN doctors d ON d.id = p.doctor_id
                JOIN users du ON du.id = d.user_id
                LEFT JOIN prescription_dispensations pd ON pd.prescription_id = p.id
                WHERE p.patient_id IN %s ORDER BY p.issued_at""".formatted(MINE), userId, userId);
        List<Map<String, Object>> items = rows("""
                SELECT i.prescription_id, m.name AS medicine, m.strength, i.dosage, i.frequency,
                       i.duration_days, i.quantity
                FROM prescription_items i
                JOIN prescriptions p ON p.id = i.prescription_id
                JOIN medicines m ON m.id = i.medicine_id
                WHERE p.patient_id IN %s ORDER BY m.name""".formatted(MINE), userId, userId);
        for (Map<String, Object> prescription : prescriptions) {
            List<Map<String, Object>> mine = new ArrayList<>();
            for (Map<String, Object> item : items) {
                if (prescription.get("id").equals(item.get("prescriptionId"))) {
                    Map<String, Object> copy = new LinkedHashMap<>(item);
                    copy.remove("prescriptionId");
                    mine.add(copy);
                }
            }
            prescription.put("medicines", mine);
        }
        return prescriptions;
    }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.queryForList(sql, args).stream().map(this::tidy).toList();
    }

    /** camelCase keys; timestamps as instants; jsonb as JSON; binary columns dropped. */
    private Map<String, Object> tidy(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((column, value) -> {
            if (value instanceof byte[]) {
                return;
            }
            out.put(camel(column), plain(value));
        });
        return out;
    }

    private Object plain(Object value) {
        if (value instanceof java.sql.Timestamp t) {
            return t.toInstant();
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (value instanceof PGobject o) {
            try {
                return o.getValue() == null ? null : json.readTree(o.getValue());
            } catch (JsonProcessingException e) {
                return o.getValue();
            }
        }
        return value;
    }

    private static String camel(String column) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : column.toCharArray()) {
            if (c == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return sb.toString();
    }
}
