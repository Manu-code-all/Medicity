package com.medicity.records;

import com.medicity.audit.AuditLog;
import com.medicity.common.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Your clinic already has records for you. Accept?"
 *
 * <p>A clinic's imported record (V48) is the clinic's, and describes a person
 * who may not know it exists. It becomes visible to that person, and is linked
 * to their account, only when <em>all</em> of these hold:
 * <ol>
 *   <li>the account's email address has been proved by a code emailed to it
 *       (V49), because sign-up checks nothing and anyone can register with
 *       anyone's address;</li>
 *   <li>the record's email is that address (a phone number is never used: it
 *       cannot be proved);</li>
 *   <li>the person says yes, here, for that record. Saying no is remembered,
 *       so the question is not asked again.</li>
 * </ol>
 * Until then an offer shows only the name of the doctor who holds the record.
 * Nothing the clinic wrote, not even that the patient's name is in it, is shown.
 * Without a verified address the answer is the same for everyone, so it also
 * does not reveal whether any clinic holds a record for an address.
 */
@Service
@RequiredArgsConstructor
public class ClinicRecordLinks {

    private final JdbcTemplate jdbc;
    private final AuditLog auditLog;
    private final Clock clock;

    /** A record offered to the person; the clinic's name only. */
    public record Offer(UUID id, String clinicName) {}

    /** A record the person accepted: everything the clinic holds about them. */
    public record Linked(UUID id, String clinicName, String fullName, String phone, String email,
                         LocalDate dateOfBirth, String gender, String bloodGroup, String allergies, String notes,
                         java.time.Instant acceptedAt) {}

    public record View(boolean emailVerified, List<Offer> offers, List<Linked> linked) {}

    @Transactional(readOnly = true)
    public View view(UUID userId) {
        List<Map<String, Object>> account = jdbc.queryForList(
                "SELECT email, email_verified_at FROM users WHERE id = ? AND deleted_at IS NULL", userId);
        if (account.isEmpty()) {
            throw new NotFoundException("Account", userId);
        }
        boolean verified = account.get(0).get("email_verified_at") != null;
        if (!verified) {
            return new View(false, List.of(), List.of());
        }
        String email = (String) account.get(0).get("email");
        List<Offer> offers = jdbc.query("""
                SELECT r.id, o.full_name AS clinic
                FROM clinic_records r JOIN users o ON o.id = r.owner_id AND r.owner_kind = 'DOCTOR'
                WHERE r.email = ? AND r.link_state = 'UNLINKED'
                ORDER BY r.created_at""", (rs, n) -> new Offer(rs.getObject("id", UUID.class), rs.getString("clinic")), email);
        List<Linked> linked = jdbc.query("""
                SELECT r.id, o.full_name AS clinic, r.full_name, r.phone, r.email, r.date_of_birth, r.gender,
                       r.blood_group, r.allergies, r.notes, r.decided_at
                FROM clinic_records r JOIN users o ON o.id = r.owner_id AND r.owner_kind = 'DOCTOR'
                WHERE r.linked_user_id = ? AND r.link_state = 'ACCEPTED'
                ORDER BY r.decided_at""", (rs, n) -> new Linked(rs.getObject("id", UUID.class), rs.getString("clinic"),
                rs.getString("full_name"), rs.getString("phone"), rs.getString("email"),
                rs.getObject("date_of_birth", LocalDate.class), rs.getString("gender"), rs.getString("blood_group"),
                rs.getString("allergies"), rs.getString("notes"), rs.getTimestamp("decided_at").toInstant()), userId);
        return new View(true, offers, linked);
    }

    /** Yes: links the record to this account's own patient profile. Anything not offered to this account is a 404. */
    @Transactional
    public void accept(UUID userId, UUID recordId) {
        Timestamp now = Timestamp.from(clock.instant());
        int changed = jdbc.update("""
                UPDATE clinic_records SET link_state = 'ACCEPTED', linked_user_id = ?,
                       linked_patient_id = (SELECT id FROM patients WHERE user_id = ?), decided_at = ?, updated_at = ?
                WHERE id = ? AND link_state = 'UNLINKED'
                  AND email = (SELECT email FROM users WHERE id = ? AND email_verified_at IS NOT NULL AND deleted_at IS NULL)""",
                userId, userId, now, now, recordId, userId);
        if (changed == 0) {
            throw new NotFoundException("Clinic record", recordId);
        }
        auditLog.recordChange("CLINIC_RECORD_ACCEPTED", "CLINIC_RECORD", recordId, Map.of());
    }

    /** No: the record stays the clinic's, is never shown, and is not offered again. */
    @Transactional
    public void decline(UUID userId, UUID recordId) {
        Timestamp now = Timestamp.from(clock.instant());
        int changed = jdbc.update("""
                UPDATE clinic_records SET link_state = 'DECLINED', decided_at = ?, updated_at = ?
                WHERE id = ? AND link_state = 'UNLINKED'
                  AND email = (SELECT email FROM users WHERE id = ? AND email_verified_at IS NOT NULL AND deleted_at IS NULL)""",
                now, now, recordId, userId);
        if (changed == 0) {
            throw new NotFoundException("Clinic record", recordId);
        }
        auditLog.recordChange("CLINIC_RECORD_DECLINED", "CLINIC_RECORD", recordId, Map.of());
    }
}
