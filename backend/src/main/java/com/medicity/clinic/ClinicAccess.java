package com.medicity.clinic;

import com.medicity.audit.AuditLog;
import com.medicity.common.ForbiddenException;
import com.medicity.common.NotFoundException;
import com.medicity.security.AppUserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which clinic the signed-in staff member works for: the one place that is
 * decided, so no service takes a clinic id from a request.
 *
 * <p>Every clinic-scoped query is given {@link Staff#clinicId()} from here. An
 * id in a path or body (a visit, a doctor, a staff account) is then checked
 * against that clinic, and one that belongs to another clinic answers exactly
 * like one that does not exist (404) and is audited, so a receptionist cannot
 * use the API to find out what another clinic holds.
 */
@Component
@RequiredArgsConstructor
public class ClinicAccess {

    /** Who is calling and where they work. */
    public record Staff(UUID userId, UUID clinicId, String staffRole, ZoneId zone) {
        public boolean isAdmin() {
            return "CLINIC_ADMIN".equals(staffRole);
        }
    }

    private final JdbcTemplate jdbc;
    private final AuditLog auditLog;

    /**
     * The caller's clinic. Refused (403) when the account has no staff row, the
     * row's role disagrees with the account's, or the clinic has been
     * deactivated by the platform.
     */
    public Staff resolve(AppUserPrincipal principal) {
        List<Staff> rows = jdbc.query("""
                SELECT cs.clinic_id, cs.staff_role, c.active, c.timezone
                FROM clinic_staff cs JOIN clinics c ON c.id = cs.clinic_id
                WHERE cs.user_id = ?
                """, (rs, i) -> {
            if (!rs.getBoolean("active")) {
                return null;
            }
            return new Staff(principal.getId(), rs.getObject("clinic_id", UUID.class),
                    rs.getString("staff_role"), zone(rs.getString("timezone")));
        }, principal.getId());
        Staff staff = rows.isEmpty() ? null : rows.get(0);
        if (staff == null || !staff.staffRole().equals(principal.getRole().name())) {
            auditLog.recordIndependently("ACCESS_DENIED", "CLINIC", null, AuditLog.Outcome.DENIED,
                    Map.of("operation", "clinic-staff", "reason", rows.isEmpty() ? "no clinic" : "clinic inactive or role mismatch"));
            throw new ForbiddenException("Your account is not linked to an active clinic");
        }
        return staff;
    }

    /**
     * What to throw for an id that is not this clinic's: a 404 like any missing
     * id, with the attempt recorded in its own transaction (the request is about
     * to fail and roll back).
     */
    public NotFoundException notOurs(Staff staff, String what, String entityType, Object id, String operation) {
        auditLog.recordIndependently("ACCESS_DENIED", entityType, id, AuditLog.Outcome.DENIED,
                Map.of("operation", operation, "clinicId", staff.clinicId()));
        return new NotFoundException(what, id);
    }

    /** A stored zone id, falling back to India time rather than failing a request on bad data. */
    static ZoneId zone(String id) {
        try {
            return ZoneId.of(id);
        } catch (DateTimeException e) {
            return ZoneId.of("Asia/Kolkata");
        }
    }
}
