package com.medicity.clinic;

import com.medicity.audit.AuditLog;
import com.medicity.clinic.ClinicAccess.Staff;
import com.medicity.clinical.VisitService;
import com.medicity.common.ValidationException;
import com.medicity.patient.Patient;
import com.medicity.patient.PatientRepository;
import com.medicity.queue.QueueService;
import com.medicity.scheduling.Appointment;
import com.medicity.scheduling.AppointmentRepository;
import com.medicity.scheduling.AppointmentStatus;
import com.medicity.scheduling.BookingService;
import com.medicity.user.Role;
import com.medicity.user.User;
import com.medicity.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A clinic's front desk: today's schedule across all its doctors, arrivals,
 * no-shows, walk-in tokens, and booking or cancelling for a patient on the
 * phone.
 *
 * <p><b>Scope.</b> Each method takes the caller's {@link Staff} and works on
 * that clinic only. A doctor, slot, visit or desk patient that belongs to
 * another clinic is refused as if it did not exist ({@link ClinicAccess#notOurs}).
 * The booking, cancelling, no-show and walk-in rules are the existing ones
 * ({@link BookingService}, {@link VisitService}, {@link QueueService}); this
 * class only decides whether the caller may use them on that row.
 *
 * <p><b>Patients.</b> The desk names a patient by an id it was given for a
 * patient its own clinic recorded, by the email of a Medicity account (the
 * patient reads it out on the phone), or by details of someone new, who is
 * recorded for this clinic only.
 */
@Service
@RequiredArgsConstructor
public class FrontDeskService {

    /** Arrival can be marked from this long before a visit's start to this long after. */
    static final Duration ARRIVAL_WINDOW = Duration.ofHours(12);

    private final NamedParameterJdbcTemplate named;
    private final JdbcTemplate jdbc;
    private final ClinicAccess access;
    private final BookingService booking;
    private final VisitService visits;
    private final QueueService queue;
    private final AppointmentRepository appointments;
    private final PatientRepository patients;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuditLog auditLog;
    private final Clock clock;

    // --- shapes -------------------------------------------------------------------

    public record DoctorLine(UUID id, String fullName, String specialization, boolean verified,
                             boolean walkInsOpen, int waiting) {}

    public record VisitLine(UUID id, UUID doctorId, String doctorName, Instant scheduledAt, Instant endsAt,
                            String status, String patientName, String patientPhone, String reason,
                            String visitType, Instant arrivedAt, boolean bookedByDesk) {}

    public record Today(LocalDate date, String timezone, List<DoctorLine> doctors, List<VisitLine> visits,
                        List<QueueService.Token> walkIns) {}

    /** Exactly one of the three says who the patient is. */
    public record PatientRef(UUID patientId, String email, NewPatient newPatient) {}

    public record NewPatient(String fullName, String phone, int ageYears, Patient.Gender gender) {}

    public record PatientHit(UUID patientId, String email, String fullName, String phone, String source) {}

    // --- today ----------------------------------------------------------------------

    /** The clinic's visits on {@code date} (its own time zone; default today) and today's walk-in lines. */
    @Transactional(readOnly = true)
    public Today today(Staff staff, LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now(clock.withZone(staff.zone()));
        Instant from = day.atStartOfDay(staff.zone()).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(staff.zone()).toInstant();

        List<VisitLine> lines = named.query("""
                SELECT a.id, d.id AS doctor_id, du.full_name AS doctor_name, s.starts_at, s.ends_at, a.status, a.reason,
                       a.visit_type, a.arrived_at, a.booked_by_user_id IS NOT NULL AS by_desk,
                       COALESCE(pu.full_name, p.full_name) AS patient_name, pu.phone AS patient_phone
                FROM doctors d
                JOIN users du ON du.id = d.user_id
                JOIN appointment_slots s ON s.doctor_id = d.id
                JOIN appointments a ON a.slot_id = s.id
                JOIN patients p ON p.id = a.patient_id
                LEFT JOIN users pu ON pu.id = p.user_id
                WHERE d.clinic_id = :clinic AND s.starts_at >= :from AND s.starts_at < :to
                ORDER BY s.starts_at, du.full_name
                """, new MapSqlParameterSource("clinic", staff.clinicId())
                        .addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to)),
                (rs, i) -> new VisitLine(rs.getObject("id", UUID.class), rs.getObject("doctor_id", UUID.class),
                        rs.getString("doctor_name"), rs.getTimestamp("starts_at").toInstant(),
                        rs.getTimestamp("ends_at").toInstant(), rs.getString("status"),
                        rs.getString("patient_name"), rs.getString("patient_phone"), rs.getString("reason"),
                        rs.getString("visit_type"),
                        rs.getTimestamp("arrived_at") == null ? null : rs.getTimestamp("arrived_at").toInstant(),
                        rs.getBoolean("by_desk")));

        record Doc(UUID id, String name, String specialization, boolean verified) {}
        List<Doc> docs = jdbc.query("""
                SELECT d.id, u.full_name, d.specialization, d.verified_at IS NOT NULL AS verified
                FROM doctors d JOIN users u ON u.id = d.user_id
                WHERE d.clinic_id = ? AND u.enabled ORDER BY u.full_name
                """, (rs, i) -> new Doc(rs.getObject("id", UUID.class), rs.getString("full_name"),
                rs.getString("specialization"), rs.getBoolean("verified")), staff.clinicId());
        List<UUID> ids = docs.stream().map(Doc::id).toList();
        List<QueueService.Token> walkIns = queue.deskFor(ids);
        Set<UUID> closed = queue.closedToday(ids);
        Map<UUID, Long> waiting = walkIns.stream().filter(t -> t.status().equals("WAITING"))
                .collect(Collectors.groupingBy(QueueService.Token::doctorId, Collectors.counting()));
        List<DoctorLine> doctorLines = docs.stream()
                .map(d -> new DoctorLine(d.id(), d.name(), d.specialization(), d.verified(),
                        d.verified() && !closed.contains(d.id()), waiting.getOrDefault(d.id(), 0L).intValue()))
                .toList();
        return new Today(day, staff.zone().getId(), doctorLines, lines, walkIns);
    }

    // --- one visit ---------------------------------------------------------------------

    /** The patient is here. Only a booked visit, within twelve hours of its time. Marking twice changes nothing. */
    @Transactional
    public VisitLine markArrived(Staff staff, UUID appointmentId) {
        Appointment visit = ownVisit(staff, appointmentId, "mark-arrived");
        if (visit.getStatus() != AppointmentStatus.BOOKED) {
            throw new ValidationException("INVALID_VISIT_STATE",
                    "This visit is %s, so the patient cannot be marked as arrived"
                            .formatted(visit.getStatus().name().toLowerCase().replace('_', ' ')));
        }
        if (visit.getArrivedAt() == null) {
            Instant now = clock.instant();
            if (Duration.between(visit.getScheduledAt(), now).abs().compareTo(ARRIVAL_WINDOW) > 0) {
                throw new ValidationException("NOT_TODAY", "This visit is not today");
            }
            visit.setArrivedAt(now);
            appointments.saveAndFlush(visit);
            auditLog.recordChange("CLINIC_VISIT_ARRIVED", "APPOINTMENT", appointmentId,
                    Map.of("clinicId", staff.clinicId(), "patientId", visit.getPatient().getId()));
        }
        return line(staff, appointmentId);
    }

    /** The patient did not come. Same rules as the doctor's own "did not come": the start time must have passed. */
    @Transactional
    public VisitLine markNoShow(Staff staff, UUID appointmentId) {
        visits.markNoShowAtDesk(appointmentId, staff.clinicId());
        return line(staff, appointmentId);
    }

    /** Cancels a visit for a patient who phoned, freeing the slot; the patient is notified if they have an account. */
    @Transactional
    public VisitLine cancel(Staff staff, UUID appointmentId, String reason) {
        ownVisit(staff, appointmentId, "cancel");
        booking.cancel(appointmentId, blankToNull(reason) == null ? "Cancelled at the front desk" : reason.trim());
        auditLog.recordChange("CLINIC_VISIT_CANCELLED", "APPOINTMENT", appointmentId,
                Map.of("clinicId", staff.clinicId()));
        return line(staff, appointmentId);
    }

    /** Books a slot of one of the clinic's doctors for a patient on the phone. */
    @Transactional
    public VisitLine book(Staff staff, UUID slotId, PatientRef who, String reason) {
        UUID clinicOfSlot = jdbc.query("""
                SELECT d.clinic_id FROM appointment_slots s JOIN doctors d ON d.id = s.doctor_id WHERE s.id = ?
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, slotId);
        if (!staff.clinicId().equals(clinicOfSlot)) {
            throw access.notOurs(staff, "Slot", "APPOINTMENT_SLOT", slotId, "desk-book");
        }
        Patient patient = patientFor(staff, who);
        Appointment booked = booking.book(slotId, patient.getId(), blankToNull(reason));
        jdbc.update("UPDATE appointments SET booked_by_user_id = ? WHERE id = ?", staff.userId(), booked.getId());
        auditLog.recordChange("CLINIC_VISIT_BOOKED", "APPOINTMENT", booked.getId(),
                Map.of("clinicId", staff.clinicId(), "patientId", patient.getId(), "slotId", slotId));
        return line(staff, booked.getId());
    }

    /** A walk-in token for any of the clinic's doctors, in the doctor's ordinary queue. */
    @Transactional
    public QueueService.Token addWalkIn(Staff staff, UUID doctorId, PatientRef who, String reason) {
        UUID clinicOfDoctor = jdbc.query("SELECT clinic_id FROM doctors WHERE id = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, doctorId);
        if (!staff.clinicId().equals(clinicOfDoctor)) {
            throw access.notOurs(staff, "Doctor", "DOCTOR", doctorId, "desk-walk-in");
        }
        Patient patient = patientFor(staff, who);
        QueueService.Token token = queue.join(doctorId, patient, reason, staff.userId());
        auditLog.recordChange("CLINIC_WALK_IN_ADDED", "QUEUE_TOKEN", token.id(),
                Map.of("clinicId", staff.clinicId(), "doctorId", doctorId, "patientId", patient.getId(),
                        "tokenNo", token.tokenNo()));
        return token;
    }

    // --- patients -------------------------------------------------------------------------

    /**
     * People the desk can pick: this clinic's own recorded patients by name or
     * phone, and, when the search is a whole email address, the Medicity account
     * with that address. Searching is audited.
     */
    @Transactional
    public List<PatientHit> searchPatients(Staff staff, String q) {
        String text = q == null ? "" : q.trim();
        if (text.length() < 2) {
            return List.of();
        }
        List<PatientHit> hits = new ArrayList<>(jdbc.query("""
                SELECT p.id, u.full_name, u.phone FROM patients p JOIN users u ON u.id = p.user_id
                WHERE p.registered_by_clinic_id = ?
                  AND (lower(u.full_name) LIKE '%' || lower(?) || '%' OR u.phone LIKE '%' || ? || '%')
                ORDER BY u.full_name LIMIT 20
                """, (rs, i) -> new PatientHit(rs.getObject("id", UUID.class), null, rs.getString("full_name"),
                rs.getString("phone"), "CLINIC"), staff.clinicId(), escapeLike(text), escapeLike(text)));
        if (text.contains("@")) {
            hits.addAll(jdbc.query("""
                    SELECT u.email, u.full_name, u.phone FROM users u JOIN patients p ON p.user_id = u.id
                    WHERE u.email = lower(?) AND u.role = 'PATIENT' AND u.enabled AND p.registered_by_clinic_id IS NULL
                    """, (rs, i) -> new PatientHit(null, rs.getString("email"), rs.getString("full_name"),
                    rs.getString("phone"), "ACCOUNT"), text));
        }
        auditLog.recordChange("CLINIC_PATIENT_SEARCHED", "CLINIC", staff.clinicId(),
                Map.of("clinicId", staff.clinicId(), "results", hits.size(), "byEmail", text.contains("@")));
        return hits;
    }

    /** Records someone with no Medicity account for this clinic's desk (or finds the one already recorded). */
    @Transactional
    public PatientHit registerPatient(Staff staff, NewPatient n) {
        Patient p = newPatient(staff, n);
        auditLog.recordChange("CLINIC_PATIENT_REGISTERED", "PATIENT", p.getId(), Map.of("clinicId", staff.clinicId()));
        return new PatientHit(p.getId(), null, n.fullName().trim(), blankToNull(n.phone()), "CLINIC");
    }

    private Patient patientFor(Staff staff, PatientRef who) {
        int given = (who == null ? 0 : (who.patientId() != null ? 1 : 0) + (who.email() != null && !who.email().isBlank() ? 1 : 0)
                + (who.newPatient() != null ? 1 : 0));
        if (given != 1) {
            throw new ValidationException("PATIENT_REQUIRED",
                    "Say who the patient is: a patient you recorded, a Medicity account's email, or their details");
        }
        if (who.patientId() != null) {
            return patients.findWithUserById(who.patientId())
                    .filter(p -> staff.clinicId().equals(p.getRegisteredByClinicId()))
                    .orElseThrow(() -> access.notOurs(staff, "Patient", "PATIENT", who.patientId(), "desk-patient"));
        }
        if (who.email() != null && !who.email().isBlank()) {
            return users.findByEmail(who.email().trim().toLowerCase())
                    .filter(u -> u.getRole() == Role.PATIENT && u.isEnabled())
                    .flatMap(u -> patients.findWithUserByUserId(u.getId()))
                    .filter(p -> p.getRegisteredByClinicId() == null)
                    .orElseThrow(() -> new ValidationException("PATIENT_NOT_FOUND",
                            "There is no patient account with that email address"));
        }
        return newPatient(staff, who.newPatient());
    }

    private Patient newPatient(Staff staff, NewPatient n) {
        if (n.fullName() == null || n.fullName().isBlank()) {
            throw new ValidationException("PATIENT_NAME_REQUIRED", "Enter the patient's name");
        }
        String name = n.fullName().trim();
        String phone = blankToNull(n.phone());
        if (phone != null) {
            // The same caller ringing again is the same person, not a second record.
            List<UUID> existing = jdbc.queryForList("""
                    SELECT p.id FROM patients p JOIN users u ON u.id = p.user_id
                    WHERE p.registered_by_clinic_id = ? AND u.phone = ? AND lower(u.full_name) = lower(?)
                    """, UUID.class, staff.clinicId(), phone, name);
            if (!existing.isEmpty()) {
                return patients.findWithUserById(existing.get(0)).orElseThrow();
            }
        }
        // An ordinary patient row behind an account nobody can sign in to (V46).
        User account = users.saveAndFlush(accountFor(name, phone));
        return patients.saveAndFlush(Patient.builder()
                .user(account)
                .registeredByClinicId(staff.clinicId())
                .dateOfBirth(LocalDate.now(clock).minusYears(Math.max(0, Math.min(n.ageYears(), 120))))
                .gender(n.gender() == null ? Patient.Gender.UNDISCLOSED : n.gender())
                .build());
    }

    private User accountFor(String name, String phone) {
        User account = User.builder()
                .passwordHash(passwordEncoder.encode(UUID.randomUUID().toString()))
                .fullName(name)
                .phone(phone)
                .role(Role.PATIENT)
                .enabled(false)
                .build();
        account.setEmail("desk-" + UUID.randomUUID() + "@walkin.invalid");
        return account;
    }

    // --- helpers ----------------------------------------------------------------------------

    /** A visit on one of this clinic's doctors' calendars, or a 404 (audited) for anything else. */
    private Appointment ownVisit(Staff staff, UUID appointmentId, String operation) {
        return appointments.findByIdWithDetails(appointmentId)
                .filter(v -> staff.clinicId().equals(v.getSlot().getDoctor().getClinicId()))
                .orElseThrow(() -> access.notOurs(staff, "Visit", "APPOINTMENT", appointmentId, operation));
    }

    private VisitLine line(Staff staff, UUID appointmentId) {
        Map<UUID, VisitLine> found = new HashMap<>();
        for (VisitLine v : named.query("""
                SELECT a.id, d.id AS doctor_id, du.full_name AS doctor_name, s.starts_at, s.ends_at, a.status, a.reason,
                       a.visit_type, a.arrived_at, a.booked_by_user_id IS NOT NULL AS by_desk,
                       COALESCE(pu.full_name, p.full_name) AS patient_name, pu.phone AS patient_phone
                FROM appointments a
                JOIN appointment_slots s ON s.id = a.slot_id
                JOIN doctors d ON d.id = s.doctor_id AND d.clinic_id = :clinic
                JOIN users du ON du.id = d.user_id
                JOIN patients p ON p.id = a.patient_id
                LEFT JOIN users pu ON pu.id = p.user_id
                WHERE a.id = :id
                """, new MapSqlParameterSource("clinic", staff.clinicId()).addValue("id", appointmentId),
                (rs, i) -> new VisitLine(rs.getObject("id", UUID.class), rs.getObject("doctor_id", UUID.class),
                        rs.getString("doctor_name"), rs.getTimestamp("starts_at").toInstant(),
                        rs.getTimestamp("ends_at").toInstant(), rs.getString("status"),
                        rs.getString("patient_name"), rs.getString("patient_phone"), rs.getString("reason"),
                        rs.getString("visit_type"),
                        rs.getTimestamp("arrived_at") == null ? null : rs.getTimestamp("arrived_at").toInstant(),
                        rs.getBoolean("by_desk")))) {
            found.put(v.id(), v);
        }
        return found.get(appointmentId);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** A search term is text to find, not a pattern: % and _ mean themselves. */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
