package com.medicity.clinic;

import com.medicity.audit.AuditLog;
import com.medicity.clinic.ClinicAccess.Staff;
import com.medicity.common.ConflictException;
import com.medicity.common.Constraints;
import com.medicity.common.NotFoundException;
import com.medicity.common.ValidationException;
import com.medicity.doctor.Doctor;
import com.medicity.doctor.DoctorRepository;
import com.medicity.doctor.DoctorSignUpService;
import com.medicity.outbox.Outbox;
import com.medicity.security.AuthService;
import com.medicity.security.RefreshTokenStore;
import com.medicity.user.Role;
import com.medicity.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A clinic's own business: its profile, the doctors and receptionists it
 * adds, and its report. Every method takes the caller's {@link Staff} (from
 * {@link ClinicAccess}) and works only on that clinic.
 *
 * <p><b>Accounts for staff are created with a temporary password</b>, shown
 * once to the administrator who created them, and the account must change it
 * before it can do anything else (V46, {@code JwtAuthenticationFilter}). An
 * emailed invite was the alternative; it needs a working email provider and a
 * link that expires, and this needs neither, so it was the simpler one. The
 * administrator hands the password over in person or by phone.
 */
@Service
@RequiredArgsConstructor
public class ClinicService {

    /** Longest report one request may ask for. */
    static final int MAX_REPORT_DAYS = 92;

    private static final String PASSWORD_ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String UQ_REGISTRATION = "uq_doctors_license";

    private final ClinicRepository clinics;
    private final DoctorRepository doctors;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final AuthService authService;
    private final PasswordEncoder passwordEncoder;
    private final ClinicAccess access;
    private final RefreshTokenStore refreshTokens;
    private final AuditLog auditLog;
    private final Outbox outbox;
    private final Clock clock;

    // --- shapes ---------------------------------------------------------------

    public record Profile(UUID id, String name, String address, String city, Double latitude, Double longitude,
                          String phone, String email, String timezone, boolean active) {
        static Profile from(Clinic c) {
            return new Profile(c.getId(), c.getName(), c.getAddress(), c.getCity(), c.getLatitude(), c.getLongitude(),
                    c.getPhone(), c.getEmail(), c.getTimezone(), c.isActive());
        }
    }

    public record ProfileUpdate(String name, String address, String city, Double latitude, Double longitude,
                                String phone, String email, String timezone) {}

    /** A person on the clinic's roster: a doctor, a receptionist or an administrator. */
    public record StaffMember(UUID userId, String fullName, String email, String phone, String role,
                              UUID doctorId, String specialization, boolean verified, boolean enabled,
                              boolean mustChangePassword) {}

    /** What the administrator is shown once after creating or resetting an account. */
    public record Credentials(UUID userId, String email, String fullName, String role, String temporaryPassword) {}

    public record NewDoctor(String fullName, String email, String phone, String specialization,
                            String medicalCouncil, String registrationNumber, String qualification,
                            int yearsExperience, BigDecimal consultationFee) {}

    public record NewReceptionist(String fullName, String email, String phone) {}

    public record NewClinic(ProfileUpdate clinic, String adminFullName, String adminEmail, String adminPhone) {}

    public record Created(Profile clinic, Credentials admin) {}

    public record ClinicRow(Profile clinic, int doctors, int staff) {}

    public record DayRow(LocalDate date, int scheduled, int completed, int noShows, int cancellations,
                         int walkIns, int walkInsSeen, int bookedByDesk) {}

    public record Report(LocalDate from, LocalDate to, String timezone, List<DayRow> days, DayRow totals) {}

    // --- profile ----------------------------------------------------------------

    @Transactional(readOnly = true)
    public Profile profile(Staff staff) {
        return Profile.from(require(staff.clinicId()));
    }

    @Transactional
    public Profile update(Staff staff, ProfileUpdate u) {
        Clinic c = require(staff.clinicId());
        List<String> changed = apply(c, u);
        clinics.save(c);
        auditLog.recordChange("CLINIC_UPDATED", "CLINIC", c.getId(), Map.of("clinicId", c.getId(), "fields", changed));
        return Profile.from(c);
    }

    private List<String> apply(Clinic c, ProfileUpdate u) {
        if (u.name() == null || u.name().isBlank()) {
            throw new ValidationException("CLINIC_NAME_REQUIRED", "A clinic needs a name");
        }
        if ((u.latitude() == null) != (u.longitude() == null)) {
            throw new ValidationException("LOCATION_INCOMPLETE", "Send both latitude and longitude, or neither");
        }
        String zone = u.timezone() == null || u.timezone().isBlank() ? c.getTimezone() : u.timezone().trim();
        if (!ZoneId.getAvailableZoneIds().contains(zone)) {
            throw new ValidationException("UNKNOWN_TIMEZONE", "Not a time zone: " + zone);
        }
        List<String> changed = new ArrayList<>();
        change(changed, "name", c.getName(), u.name().trim(), c::setName);
        change(changed, "address", c.getAddress(), blankToNull(u.address()), c::setAddress);
        change(changed, "city", c.getCity(), blankToNull(u.city()), c::setCity);
        change(changed, "latitude", c.getLatitude(), u.latitude(), c::setLatitude);
        change(changed, "longitude", c.getLongitude(), u.longitude(), c::setLongitude);
        change(changed, "phone", c.getPhone(), blankToNull(u.phone()), c::setPhone);
        change(changed, "email", c.getEmail(), blankToNull(u.email()) == null ? null : u.email().trim().toLowerCase(),
                c::setEmail);
        change(changed, "timezone", c.getTimezone(), zone, c::setTimezone);
        return changed;
    }

    private static <T> void change(List<String> changed, String field, T from, T to,
                                   java.util.function.Consumer<T> set) {
        if (!java.util.Objects.equals(from, to)) {
            changed.add(field);
            set.accept(to);
        }
    }

    // --- people -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<StaffMember> staff(Staff staff) {
        return jdbc.query("""
                SELECT u.id, u.full_name, u.email, u.phone, u.role, u.enabled, u.must_change_password,
                       d.id AS doctor_id, d.specialization, d.verified_at IS NOT NULL AS verified
                FROM users u
                LEFT JOIN doctors d ON d.user_id = u.id
                LEFT JOIN clinic_staff cs ON cs.user_id = u.id
                WHERE d.clinic_id = ? OR cs.clinic_id = ?
                ORDER BY CASE u.role WHEN 'CLINIC_ADMIN' THEN 0 WHEN 'DOCTOR' THEN 1 ELSE 2 END, u.full_name
                """, (rs, i) -> new StaffMember(rs.getObject("id", UUID.class), rs.getString("full_name"),
                rs.getString("email"), rs.getString("phone"), rs.getString("role"),
                rs.getObject("doctor_id", UUID.class), rs.getString("specialization"), rs.getBoolean("verified"),
                rs.getBoolean("enabled"), rs.getBoolean("must_change_password")),
                staff.clinicId(), staff.clinicId());
    }

    /**
     * Adds a doctor to the clinic. They sign in with the temporary password,
     * change it, and set their hours. They are not listed or bookable until the
     * platform administrator has checked the registration number, exactly as for
     * a doctor who signed up alone ({@link DoctorSignUpService}).
     */
    @Transactional
    public Credentials addDoctor(Staff staff, NewDoctor d) {
        if (!DoctorSignUpService.SPECIALITIES.contains(d.specialization())) {
            throw new ValidationException("UNKNOWN_SPECIALITY", "Choose one of the specialities offered");
        }
        String password = temporaryPassword();
        User user = authService.createStaffAccount(d.email(), password, d.fullName(), d.phone(), Role.DOCTOR);
        Doctor doctor = Doctor.builder()
                .user(user)
                .clinic(clinics.getReferenceById(staff.clinicId()))
                .specialization(d.specialization())
                .medicalCouncil(d.medicalCouncil().trim())
                .licenseNumber(d.registrationNumber().trim().toUpperCase())
                .qualification(d.qualification().trim())
                .yearsExperience(d.yearsExperience())
                .consultationFee(d.consultationFee())
                .verifiedAt(null)
                .build();
        try {
            doctor = doctors.saveAndFlush(doctor);
        } catch (DataIntegrityViolationException e) {
            if (Constraints.isViolationOf(e, UQ_REGISTRATION)) {
                throw new ConflictException("REGISTRATION_TAKEN",
                        "A doctor with this registration number is already on Medicity");
            }
            throw e;
        }
        auditLog.recordChange("CLINIC_DOCTOR_ADDED", "DOCTOR", doctor.getId(), Map.of(
                "clinicId", staff.clinicId(), "userId", user.getId(), "registration", doctor.getLicenseNumber()));
        outbox.publish(Outbox.DOCTOR_REGISTERED, doctor.getId(), Map.of(
                "doctorName", user.getFullName(),
                "specialization", doctor.getSpecialization(),
                "medicalCouncil", doctor.getMedicalCouncil(),
                "registrationNumber", doctor.getLicenseNumber()));
        return new Credentials(user.getId(), user.getEmail(), user.getFullName(), Role.DOCTOR.name(), password);
    }

    @Transactional
    public Credentials addReceptionist(Staff staff, NewReceptionist r) {
        String password = temporaryPassword();
        User user = authService.createStaffAccount(r.email(), password, r.fullName(), r.phone(), Role.RECEPTIONIST);
        jdbc.update("INSERT INTO clinic_staff (clinic_id, user_id, staff_role) VALUES (?, ?, ?)",
                staff.clinicId(), user.getId(), Role.RECEPTIONIST.name());
        auditLog.recordChange("CLINIC_STAFF_ADDED", "USER", user.getId(),
                Map.of("clinicId", staff.clinicId(), "role", Role.RECEPTIONIST.name()));
        return new Credentials(user.getId(), user.getEmail(), user.getFullName(), Role.RECEPTIONIST.name(), password);
    }

    /** A new temporary password for a doctor or receptionist of this clinic, who must change it at their next sign-in. */
    @Transactional
    public Credentials resetPassword(Staff staff, UUID userId) {
        Map<String, Object> target = requireManageable(staff, userId, "reset-password");
        String password = temporaryPassword();
        String hash = passwordEncoder.encode(password);
        jdbc.update("UPDATE users SET password_hash = ?, must_change_password = TRUE, updated_at = now(), "
                + "version = version + 1 WHERE id = ?", hash, userId);
        refreshTokens.revokeAllFor(userId, clock.instant());
        auditLog.recordChange("CLINIC_STAFF_PASSWORD_RESET", "USER", userId, Map.of("clinicId", staff.clinicId()));
        return new Credentials(userId, (String) target.get("email"), (String) target.get("full_name"),
                (String) target.get("role"), password);
    }

    /** Turns a doctor's or receptionist's sign-in off or on. Signed-in sessions stop at once. */
    @Transactional
    public void setEnabled(Staff staff, UUID userId, boolean enabled) {
        requireManageable(staff, userId, enabled ? "enable" : "disable");
        jdbc.update("UPDATE users SET enabled = ?, updated_at = now(), version = version + 1 WHERE id = ?",
                enabled, userId);
        if (!enabled) {
            refreshTokens.revokeAllFor(userId, clock.instant());
        }
        auditLog.recordChange(enabled ? "CLINIC_STAFF_ENABLED" : "CLINIC_STAFF_DISABLED", "USER", userId,
                Map.of("clinicId", staff.clinicId()));
    }

    /**
     * An account that belongs to this clinic and that an administrator may
     * manage: a doctor or receptionist. Another clinic's account answers 404;
     * an administrator's account (including the caller's own) is refused, so
     * one administrator cannot take over another's.
     */
    private Map<String, Object> requireManageable(Staff staff, UUID userId, String operation) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT u.id, u.email, u.full_name, u.role FROM users u
                WHERE u.id = ? AND (
                      EXISTS (SELECT 1 FROM clinic_staff cs WHERE cs.user_id = u.id AND cs.clinic_id = ?)
                   OR EXISTS (SELECT 1 FROM doctors d WHERE d.user_id = u.id AND d.clinic_id = ?))
                """, userId, staff.clinicId(), staff.clinicId());
        if (rows.isEmpty()) {
            throw access.notOurs(staff, "Staff member", "USER", userId, operation);
        }
        if (Role.CLINIC_ADMIN.name().equals(rows.get(0).get("role"))) {
            throw new ValidationException("ADMIN_ACCOUNT_PROTECTED",
                    "A clinic administrator's account is not managed from here");
        }
        return rows.get(0);
    }

    // --- report -----------------------------------------------------------------

    /**
     * A basic report for the clinic's own doctors over a date range (inclusive,
     * in the clinic's time zone): per day, the visits scheduled, how many were
     * completed, missed or cancelled, and the walk-in tokens given.
     *
     * <p>A visit counts on the day it was scheduled for, whenever it was booked
     * or cancelled. "Scheduled" excludes cancelled visits. Walk-in days are the
     * queue's own (India time, V25).
     */
    @Transactional(readOnly = true)
    public Report report(Staff staff, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)
                || ChronoUnit.DAYS.between(from, to) + 1 > MAX_REPORT_DAYS) {
            throw new ValidationException("INVALID_RANGE",
                    "Choose a range of at most %d days, ending on or after its start".formatted(MAX_REPORT_DAYS));
        }
        ZoneId zone = staff.zone();
        Map<LocalDate, int[]> days = new TreeMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            days.put(d, new int[6 + 1]);
        }
        var params = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("clinic", staff.clinicId())
                .addValue("from", java.sql.Timestamp.from(from.atStartOfDay(zone).toInstant()))
                .addValue("to", java.sql.Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant()))
                .addValue("tz", zone.getId())
                .addValue("fromDate", java.sql.Date.valueOf(from))
                .addValue("toDate", java.sql.Date.valueOf(to));
        named.query("""
                SELECT (s.starts_at AT TIME ZONE :tz)::date AS day,
                       count(*) FILTER (WHERE a.status <> 'CANCELLED') AS scheduled,
                       count(*) FILTER (WHERE a.status = 'COMPLETED') AS completed,
                       count(*) FILTER (WHERE a.status = 'NO_SHOW') AS no_shows,
                       count(*) FILTER (WHERE a.status = 'CANCELLED') AS cancelled,
                       count(*) FILTER (WHERE a.status <> 'CANCELLED' AND a.booked_by_user_id IS NOT NULL) AS by_desk
                FROM doctors d
                JOIN appointment_slots s ON s.doctor_id = d.id
                JOIN appointments a ON a.slot_id = s.id
                WHERE d.clinic_id = :clinic AND s.starts_at >= :from AND s.starts_at < :to
                GROUP BY 1
                """, params, rs -> {
            int[] row = days.get(rs.getDate("day").toLocalDate());
            if (row != null) {
                row[0] = rs.getInt("scheduled");
                row[1] = rs.getInt("completed");
                row[2] = rs.getInt("no_shows");
                row[3] = rs.getInt("cancelled");
                row[6] = rs.getInt("by_desk");
            }
        });
        named.query("""
                SELECT t.queue_date AS day, count(*) AS walk_ins, count(*) FILTER (WHERE t.status = 'SEEN') AS seen
                FROM doctors d JOIN queue_tokens t ON t.doctor_id = d.id
                WHERE d.clinic_id = :clinic AND t.queue_date BETWEEN :fromDate AND :toDate
                GROUP BY 1
                """, params, rs -> {
            int[] row = days.get(rs.getDate("day").toLocalDate());
            if (row != null) {
                row[4] = rs.getInt("walk_ins");
                row[5] = rs.getInt("seen");
            }
        });

        List<DayRow> rows = new ArrayList<>();
        int[] total = new int[7];
        days.forEach((date, v) -> {
            rows.add(new DayRow(date, v[0], v[1], v[2], v[3], v[4], v[5], v[6]));
            for (int i = 0; i < total.length; i++) {
                total[i] += v[i];
            }
        });
        auditLog.recordIndependently("CLINIC_REPORT_VIEWED", "CLINIC", staff.clinicId(), AuditLog.Outcome.SUCCESS,
                Map.of("clinicId", staff.clinicId(), "from", from.toString(), "to", to.toString()));
        return new Report(from, to, zone.getId(), rows,
                new DayRow(null, total[0], total[1], total[2], total[3], total[4], total[5], total[6]));
    }

    // --- the platform administrator's side ---------------------------------------

    /** Sets a clinic up with its first administrator, who gets a temporary password. */
    @Transactional
    public Created create(NewClinic n) {
        Clinic c = Clinic.builder().name("-").build();
        apply(c, n.clinic());
        c = clinics.saveAndFlush(c);
        String password = temporaryPassword();
        User admin = authService.createStaffAccount(n.adminEmail(), password, n.adminFullName(), n.adminPhone(),
                Role.CLINIC_ADMIN);
        jdbc.update("INSERT INTO clinic_staff (clinic_id, user_id, staff_role) VALUES (?, ?, ?)",
                c.getId(), admin.getId(), Role.CLINIC_ADMIN.name());
        auditLog.recordChange("CLINIC_CREATED", "CLINIC", c.getId(), Map.of("name", c.getName(), "adminUserId", admin.getId()));
        return new Created(Profile.from(c),
                new Credentials(admin.getId(), admin.getEmail(), admin.getFullName(), Role.CLINIC_ADMIN.name(), password));
    }

    @Transactional
    public Profile setActive(UUID clinicId, boolean active) {
        Clinic c = clinics.findById(clinicId).orElseThrow(() -> new NotFoundException("Clinic", clinicId));
        if (c.isActive() != active) {
            c.setActive(active);
            clinics.save(c);
            auditLog.recordChange(active ? "CLINIC_ACTIVATED" : "CLINIC_DEACTIVATED", "CLINIC", clinicId,
                    Map.of("clinicId", clinicId));
        }
        return Profile.from(c);
    }

    @Transactional(readOnly = true)
    public List<ClinicRow> all() {
        return jdbc.query("""
                SELECT c.*, (SELECT count(*) FROM doctors d WHERE d.clinic_id = c.id) AS doctor_count,
                       (SELECT count(*) FROM clinic_staff s WHERE s.clinic_id = c.id) AS staff_count
                FROM clinics c ORDER BY lower(c.name)
                """, (rs, i) -> new ClinicRow(profile(rs), rs.getInt("doctor_count"), rs.getInt("staff_count")));
    }

    // --- the public directory ----------------------------------------------------

    /** A listed clinic and how many doctors can be booked there. */
    public record Listed(UUID id, String name, String address, String city, Double latitude, Double longitude,
                         String phone, String email, int doctors) {}

    /** Active clinics with at least one bookable doctor. */
    @Transactional(readOnly = true)
    public List<Listed> directory() {
        return jdbc.query(DIRECTORY + " WHERE c.active GROUP BY c.id HAVING count(d.id) > 0 ORDER BY lower(c.name)",
                (rs, i) -> listed(rs));
    }

    @Transactional(readOnly = true)
    public Listed listed(UUID id) {
        List<Listed> found = jdbc.query(DIRECTORY + " WHERE c.active AND c.id = ? GROUP BY c.id", (rs, i) -> listed(rs), id);
        if (found.isEmpty()) {
            throw new NotFoundException("Clinic", id);
        }
        return found.get(0);
    }

    private static final String DIRECTORY = """
            SELECT c.id, c.name, c.address, c.city, c.latitude, c.longitude, c.phone, c.email, count(d.id) AS doctors
            FROM clinics c
            LEFT JOIN doctors d ON d.clinic_id = c.id AND d.verified_at IS NOT NULL
                 AND EXISTS (SELECT 1 FROM users u WHERE u.id = d.user_id AND u.enabled)
            """;

    private static Listed listed(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Listed(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("address"),
                rs.getString("city"), (Double) rs.getObject("latitude"), (Double) rs.getObject("longitude"),
                rs.getString("phone"), rs.getString("email"), rs.getInt("doctors"));
    }

    // --- helpers -------------------------------------------------------------------

    private static Profile profile(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Profile(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("address"),
                rs.getString("city"), (Double) rs.getObject("latitude"), (Double) rs.getObject("longitude"),
                rs.getString("phone"), rs.getString("email"), rs.getString("timezone"), rs.getBoolean("active"));
    }

    private Clinic require(UUID id) {
        return clinics.findById(id).orElseThrow(() -> new NotFoundException("Clinic", id));
    }

    static String temporaryPassword() {
        StringBuilder sb = new StringBuilder(14);
        for (int i = 0; i < 12; i++) {
            if (i > 0 && i % 4 == 0) {
                sb.append('-');
            }
            sb.append(PASSWORD_ALPHABET.charAt(RANDOM.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
