package com.medicity.privacy;

import com.medicity.common.ValidationException;
import com.medicity.user.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Records and checks consent (V39).
 *
 * <p>The current version of each text is configuration
 * ({@code medicity.privacy.*-version}). Changing a version and redeploying is
 * all it takes to ask everyone to agree again: {@link #status} compares what
 * an account last agreed to with what is current, and the web app shows the
 * agreement screen while anything is outstanding.
 *
 * <p>Rows are only ever inserted. Agreeing to a version twice (a double tap, a
 * retry) is one row: {@code ON CONFLICT DO NOTHING}.
 */
@Service
public class ConsentService {

    public enum Source { SIGN_UP, POLICY_UPDATE, FAMILY_MEMBER_ADDED }

    public enum Capacity { SELF, GUARDIAN_OF_MINOR, REPRESENTATIVE_OF_ADULT }

    /** One line of an account's consent status. */
    public record Item(ConsentType type, String currentVersion, String acceptedVersion, Instant acceptedAt,
                       boolean upToDate) {}

    public record Status(List<Item> items, boolean pending) {}

    private final JdbcTemplate jdbc;
    private final Map<ConsentType, String> versions = new EnumMap<>(ConsentType.class);

    public ConsentService(JdbcTemplate jdbc,
                          @Value("${medicity.privacy.terms-version}") String termsVersion,
                          @Value("${medicity.privacy.privacy-version}") String privacyVersion,
                          @Value("${medicity.privacy.health-data-version}") String healthDataVersion) {
        this.jdbc = jdbc;
        versions.put(ConsentType.TERMS_OF_SERVICE, termsVersion);
        versions.put(ConsentType.PRIVACY_NOTICE, privacyVersion);
        versions.put(ConsentType.HEALTH_DATA_PROCESSING, healthDataVersion);
    }

    public String currentVersion(ConsentType type) {
        return versions.get(type);
    }

    /** What an account of this role must have agreed to. Clinic staff handle patients' appointments, so like doctors and chemists they agree to the terms and the privacy notice (not the health-data text, which is for people whose own records are kept). The platform's own staff are provisioned internally and agree to nothing here. */
    public Set<ConsentType> requiredFor(Role role) {
        return switch (role) {
            case PATIENT -> Set.of(ConsentType.values());
            case DOCTOR, CHEMIST, CLINIC_ADMIN, RECEPTIONIST -> Set.of(ConsentType.TERMS_OF_SERVICE, ConsentType.PRIVACY_NOTICE);
            case ADMIN, PHARMACIST -> Set.of();
        };
    }

    /**
     * Sign-up: records every consent the role needs, at the current versions.
     * Joins the sign-up's transaction, so an account never exists without the
     * agreement it was created on (or the other way round).
     *
     * @param healthData true when the person ticked the separate health-data box;
     *        required for a patient
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSignUp(UUID userId, Role role, boolean healthData) {
        Set<ConsentType> required = requiredFor(role);
        if (required.contains(ConsentType.HEALTH_DATA_PROCESSING) && !healthData) {
            throw new ValidationException("HEALTH_DATA_CONSENT_REQUIRED",
                    "Medicity cannot keep your health records without your agreement to that");
        }
        for (ConsentType type : ConsentType.values()) {
            if (required.contains(type)) {
                insert(userId, null, type, Capacity.SELF, Source.SIGN_UP);
            }
        }
    }

    /**
     * The account holder agrees, for a family member they are adding, to the
     * health-data processing: as the guardian of a minor, or as the
     * representative of an adult who has asked them to manage their care.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordOnBehalf(UUID guardianUserId, UUID patientId, boolean minor, Source source) {
        insert(guardianUserId, patientId, ConsentType.HEALTH_DATA_PROCESSING,
                minor ? Capacity.GUARDIAN_OF_MINOR : Capacity.REPRESENTATIVE_OF_ADULT, source);
    }

    @Transactional(readOnly = true)
    public Status status(UUID userId, Role role) {
        Set<ConsentType> required = requiredFor(role);
        List<Item> items = new ArrayList<>();
        boolean pending = false;
        for (ConsentType type : ConsentType.values()) {
            if (!required.contains(type)) {
                continue;
            }
            String current = versions.get(type);
            // Newest first: a person who agreed to v1 and later v2 has accepted v2.
            List<Object[]> rows = jdbc.query("""
                    SELECT policy_version, given_at FROM consent_records
                    WHERE user_id = ? AND subject_patient_id IS NULL AND consent_type = ?
                    ORDER BY given_at DESC, id LIMIT 1
                    """, (rs, i) -> new Object[]{rs.getString("policy_version"),
                    rs.getTimestamp("given_at").toInstant()}, userId, type.name());
            String accepted = rows.isEmpty() ? null : (String) rows.get(0)[0];
            Instant at = rows.isEmpty() ? null : (Instant) rows.get(0)[1];
            // "Up to date" means the very version now in force: any change is a new text.
            boolean upToDate = current.equals(accepted) || hasAccepted(userId, type, current);
            pending |= !upToDate;
            items.add(new Item(type, current, accepted, at, upToDate));
        }
        return new Status(items, pending);
    }

    /**
     * The person agrees to the current version of the given texts (a policy
     * update). Agreeing to the health-data text also covers the family members
     * they manage, who are shown the same screen through them.
     */
    @Transactional
    public Status accept(UUID userId, Role role, Set<ConsentType> types) {
        Set<ConsentType> required = requiredFor(role);
        if (types.isEmpty() || !required.containsAll(types)) {
            throw new ValidationException("CONSENT_NOT_APPLICABLE", "Choose the agreements that apply to your account");
        }
        for (ConsentType type : types) {
            insert(userId, null, type, Capacity.SELF, Source.POLICY_UPDATE);
        }
        if (types.contains(ConsentType.HEALTH_DATA_PROCESSING)) {
            List<Object[]> members = jdbc.query("""
                    SELECT id, date_of_birth > (CURRENT_DATE - INTERVAL '18 years') AS minor
                    FROM patients WHERE guardian_user_id = ?
                    """, (rs, i) -> new Object[]{rs.getObject("id", UUID.class), rs.getBoolean("minor")}, userId);
            for (Object[] m : members) {
                insert(userId, (UUID) m[0], ConsentType.HEALTH_DATA_PROCESSING,
                        (Boolean) m[1] ? Capacity.GUARDIAN_OF_MINOR : Capacity.REPRESENTATIVE_OF_ADULT,
                        Source.POLICY_UPDATE);
            }
        }
        return status(userId, role);
    }

    private boolean hasAccepted(UUID userId, ConsentType type, String version) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM consent_records
                WHERE user_id = ? AND subject_patient_id IS NULL AND consent_type = ? AND policy_version = ?
                """, Integer.class, userId, type.name(), version);
        return n != null && n > 0;
    }

    private void insert(UUID userId, UUID subjectPatientId, ConsentType type, Capacity capacity, Source source) {
        // No conflict target: either partial unique index (V39) may be the one hit.
        jdbc.update("""
                INSERT INTO consent_records (user_id, subject_patient_id, consent_type, policy_version, capacity, source)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, userId, subjectPatientId, type.name(), versions.get(type), capacity.name(), source.name());
    }
}
