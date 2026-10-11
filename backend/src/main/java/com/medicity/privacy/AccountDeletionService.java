package com.medicity.privacy;

import com.medicity.audit.AuditLog;
import com.medicity.common.ConflictException;
import com.medicity.common.NotFoundException;
import com.medicity.common.ValidationException;
import com.medicity.security.LoginThrottle;
import com.medicity.common.TooManyRequestsException;
import com.medicity.user.Role;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "Delete my account" (DPDP Act erasure right; Google Play's account-deletion rule).
 *
 * <p><b>Anonymise, do not remove.</b> A patient's account row is overwritten
 * so that nobody can tell who it was, and everything that is personal and not
 * needed for a legal or safety reason is deleted. The row itself stays,
 * because the records that must be kept point at it. What goes and what stays:
 *
 * <table>
 *   <caption>Treatment of each kind of data</caption>
 *   <tr><td>Name, email, phone, sign-in number, password</td>
 *       <td>Overwritten on the account; the password with an unusable hash</td></tr>
 *   <tr><td>Address, city, emergency contact, height, weight, allergies, conditions,
 *       medicines, blood group, home location</td><td>Cleared (the account holder and each family member)</td></tr>
 *   <tr><td>Date of birth</td><td>Reduced to 1 January of the birth year: a prescription
 *       must still be readable for a patient's age</td></tr>
 *   <tr><td>Family members' names</td><td>Replaced with "Deleted family member"</td></tr>
 *   <tr><td>Sessions, sign-in codes, notifications, stored responses, queue places,
 *       waiting-list entries, pending reservations, files the patient uploaded</td><td>Deleted or ended</td></tr>
 *   <tr><td>Where medicine questions were asked from</td><td>Cleared</td></tr>
 *   <tr><td>Review comments</td><td>Cleared; the star rating stays in the doctor's average</td></tr>
 *   <tr><td>Appointments, prescriptions and their items, dispensations, the doctor's
 *       notes, the visit's follow-up messages</td>
 *       <td>Kept, no longer linked to an identifiable person: the clinical record is the
 *       doctor's and the pharmacy's, and Indian medical-practice rules require it to be
 *       kept for years (Indian Medical Council (Professional Conduct, Etiquette and Ethics)
 *       Regulations 2002, reg. 1.3: at least three years; the Telemedicine Practice
 *       Guidelines 2020 likewise). DPDP s.8(7) allows keeping data a law requires. To be
 *       confirmed by a lawyer.</td></tr>
 *   <tr><td>The audit log</td><td>Kept: append-only by design (V5, V6) and the evidence
 *       of who saw and changed records. It names the account by id, not by name; failed
 *       sign-in rows hold the email that was typed.</td></tr>
 *   <tr><td>Consent records</td><td>Kept: the proof of what was agreed and when.</td></tr>
 * </table>
 *
 * <p>Patient accounts only. A doctor's or chemist's account carries a
 * professional registration and stores that other people rely on, so
 * removing one is handled by a person (the grievance officer), not by a
 * button; the public page says so.
 */
@Service
@Slf4j
public class AccountDeletionService {

    /** Used in every "which patients are this account's" subquery: the holder and their family. */
    private static final String MY_PATIENTS = "(SELECT id FROM patients WHERE user_id = ? OR guardian_user_id = ?)";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final LoginThrottle loginThrottle;
    private final AuditLog auditLog;
    private final Clock clock;

    public AccountDeletionService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, LoginThrottle loginThrottle,
                                  AuditLog auditLog, Clock clock) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.loginThrottle = loginThrottle;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /**
     * A wrong password rolls this transaction back, which loses nothing: it has
     * changed no row yet, and the failed attempt is an audit row written in its
     * own transaction.
     *
     * @return how many rows of each kind were affected, for the audit trail and the reply
     */
    @Transactional
    public Map<String, Integer> delete(UUID userId, String rawPassword) {
        // The account row is locked for the whole deletion: two taps, or a
        // deletion racing a family-member add, cannot interleave.
        var rows = jdbc.queryForList(
                "SELECT role, email, password_hash, deleted_at FROM users WHERE id = ? FOR UPDATE", userId);
        if (rows.isEmpty() || rows.get(0).get("deleted_at") != null) {
            throw new NotFoundException("Account", userId);
        }
        Map<String, Object> user = rows.get(0);
        Role role = Role.valueOf((String) user.get("role"));
        if (role != Role.PATIENT) {
            throw new ConflictException("DELETE_BY_REQUEST",
                    "A doctor, chemist or administrator account is removed by our grievance officer, not from the app. "
                            + "See the Delete my account page for how to ask.");
        }

        String email = (String) user.get("email");
        // Re-entering the password is the guard against a borrowed phone, so it
        // is throttled like sign-in: the same rows, the same window.
        try {
            loginThrottle.check(email);
        } catch (TooManyRequestsException e) {
            auditLog.recordIndependently("LOGIN_THROTTLED", "USER", userId, AuditLog.Outcome.DENIED,
                    Map.of("email", email, "operation", "delete-account"));
            throw e;
        }
        if (rawPassword == null || !passwordEncoder.matches(rawPassword, (String) user.get("password_hash"))) {
            auditLog.recordIndependently("LOGIN_FAILED", "USER", userId, AuditLog.Outcome.DENIED,
                    Map.of("email", email, "operation", "delete-account"));
            throw new ValidationException("INCORRECT_PASSWORD", "That password is not correct");
        }

        Instant now = clock.instant();
        Timestamp ts = Timestamp.from(now);
        Map<String, Integer> counts = new LinkedHashMap<>();

        // --- end what is still live ---------------------------------------------
        counts.put("reservationsReleased", jdbc.update("""
                UPDATE reservations SET status = 'CANCELLED', ended_at = ?
                WHERE status = 'HELD' AND patient_id IN %s""".formatted(MY_PATIENTS), ts, userId, userId));
        counts.put("questionsClosed", jdbc.update("""
                UPDATE medicine_requests SET status = 'CLOSED', closed_at = ?
                WHERE status IN ('OPEN', 'RESERVED') AND patient_id IN %s""".formatted(MY_PATIENTS), ts, userId, userId));
        // Version bumped like every other writer of this row, so a doctor's
        // concurrent save fails its version check rather than overwrite.
        counts.put("visitsCancelled", jdbc.update("""
                UPDATE appointments SET status = 'CANCELLED', cancelled_at = ?, cancel_reason = 'Account deleted',
                       updated_at = ?, version = version + 1
                WHERE status = 'BOOKED' AND scheduled_at > ? AND patient_id IN %s""".formatted(MY_PATIENTS),
                ts, ts, ts, userId, userId));

        // --- delete what is personal and not a record ----------------------------
        // Delivered events about this person's visits name them; the doctor
        // already has what they need, and nothing reads a published event again.
        jdbc.update("""
                DELETE FROM outbox_events WHERE published_at IS NOT NULL AND aggregate_id IN
                  (SELECT id FROM appointments WHERE patient_id IN %s)""".formatted(MY_PATIENTS), userId, userId);
        jdbc.update("DELETE FROM queue_tokens WHERE patient_id IN " + MY_PATIENTS, userId, userId);
        jdbc.update("DELETE FROM slot_waitlist WHERE patient_id IN " + MY_PATIENTS, userId, userId);
        jdbc.update("DELETE FROM notifications WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM otp_challenges WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM idempotency_keys WHERE user_id = ?", userId);
        // Reports and photos the patient attached for the doctor.
        counts.put("filesDeleted", jdbc.update("DELETE FROM appointment_attachments WHERE uploader_user_id = ?", userId));
        // Sessions: every refresh token goes, so nothing can renew. The account
        // is also disabled below, which the request filter checks on every call,
        // so an access token already issued stops working at once too.
        counts.put("sessionsRevoked", jdbc.update("DELETE FROM refresh_tokens WHERE user_id = ?", userId));

        jdbc.update("UPDATE doctor_reviews SET comment = NULL WHERE comment IS NOT NULL AND patient_id IN "
                + MY_PATIENTS, userId, userId);
        jdbc.update("""
                UPDATE medicine_requests SET latitude = NULL, longitude = NULL, location_minimised_at = ?
                WHERE latitude IS NOT NULL AND patient_id IN %s""".formatted(MY_PATIENTS), ts, userId, userId);

        // --- anonymise what stays -------------------------------------------------
        counts.put("patientsAnonymised", jdbc.update("""
                UPDATE patients SET
                    full_name = CASE WHEN guardian_user_id IS NULL THEN full_name ELSE 'Deleted family member' END,
                    date_of_birth = make_date(extract(year FROM date_of_birth)::int, 1, 1),
                    blood_group = NULL, address_line = NULL, city = NULL, emergency_contact = NULL,
                    height_cm = NULL, weight_kg = NULL, allergies = NULL, chronic_conditions = NULL,
                    current_medications = NULL, home_latitude = NULL, home_longitude = NULL,
                    updated_at = ?, version = version + 1
                WHERE user_id = ? OR guardian_user_id = ?""", ts, userId, userId));

        // The email stays well-formed and unique (V1's CHECK and index) without
        // being anyone's address; ".invalid" is reserved and never delivers.
        jdbc.update("""
                UPDATE users SET email = ?, full_name = 'Deleted user', phone = NULL, login_phone = NULL,
                       password_hash = ?, enabled = FALSE, deleted_at = ?, updated_at = ?, version = version + 1
                WHERE id = ?""",
                "deleted-" + userId + "@deleted.invalid", passwordEncoder.encode(UUID.randomUUID().toString()),
                ts, ts, userId);

        auditLog.recordChange("ACCOUNT_DELETED", "USER", userId, new LinkedHashMap<>(counts));
        log.info("Account {} deleted (anonymised): {}", userId, counts);
        return counts;
    }
}
