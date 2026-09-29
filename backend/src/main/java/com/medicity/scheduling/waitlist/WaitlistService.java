package com.medicity.scheduling.waitlist;

import com.medicity.common.NotFoundException;
import com.medicity.common.ValidationException;
import com.medicity.doctor.Doctor;
import com.medicity.doctor.DoctorRepository;
import com.medicity.outbox.Outbox;
import com.medicity.patient.Patient;
import com.medicity.scheduling.AppointmentSlot;
import com.medicity.scheduling.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Waiting lists for a full day with a doctor. Days are India time, like the
 * clinics' calendars. Plain SQL: the interesting parts are an upsert and an
 * UPDATE ... RETURNING that claims everyone waiting in one statement.
 */
@Service
@RequiredArgsConstructor
public class WaitlistService {

    static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Kolkata");
    /** As far ahead as someone can ask to be told. */
    static final int MAX_DAYS_AHEAD = 60;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private final NamedParameterJdbcTemplate jdbc;
    private final DoctorRepository doctorRepository;
    private final Outbox outbox;
    private final Clock clock;

    public record Entry(UUID id, UUID doctorId, String doctorName, String specialization, UUID patientId,
                        String patientName, LocalDate date, String status) {}

    /** Joins (or rejoins) the waiting list for this doctor on this day. */
    @Transactional
    public Entry join(UUID doctorId, Patient patient, LocalDate date) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .filter(Doctor::isVerified)
                .orElseThrow(() -> new NotFoundException("Doctor", doctorId));
        LocalDate today = today();
        if (date.isBefore(today) || date.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            throw new ValidationException("WAITLIST_DATE",
                    "Choose a day from today up to %d days ahead".formatted(MAX_DAYS_AHEAD));
        }
        UUID id = jdbc.queryForObject("""
                INSERT INTO slot_waitlist (doctor_id, patient_id, target_date)
                VALUES (:doctor, :patient, :date)
                ON CONFLICT (doctor_id, patient_id, target_date)
                DO UPDATE SET status = 'ACTIVE', created_at = now(), notified_at = NULL
                RETURNING id
                """, new MapSqlParameterSource().addValue("doctor", doctor.getId())
                .addValue("patient", patient.getId()).addValue("date", date), UUID.class);
        return entry(id);
    }

    /** Stops waiting. Leaving something not joined is not an error. */
    @Transactional
    public void leave(UUID doctorId, UUID patientId, LocalDate date) {
        jdbc.update("""
                UPDATE slot_waitlist SET status = 'LEFT'
                WHERE doctor_id = :doctor AND patient_id = :patient AND target_date = :date
                  AND status IN ('ACTIVE', 'NOTIFIED')
                """, Map.of("doctor", doctorId, "patient", patientId, "date", date));
    }

    /** Everything this account (and the family it manages) is still waiting for, from today on. */
    public List<Entry> mine(UUID accountUserId) {
        return jdbc.query(SELECT + """
                WHERE (p.user_id = :account OR p.guardian_user_id = :account)
                  AND w.status IN ('ACTIVE', 'NOTIFIED')
                  AND w.target_date >= :today
                ORDER BY w.target_date, du.full_name
                """, Map.of("account", accountUserId, "today", today()), MAPPER);
    }

    /**
     * A booked time with this doctor was released (a cancellation or a
     * move). Everyone still waiting for that day is told, in the caller's
     * transaction: if the cancellation rolls back, nobody hears of a time
     * that never opened. A too-soon slot is not announced, since nobody
     * could book it.
     *
     * @return how many were told
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int slotReleased(AppointmentSlot slot) {
        Instant now = clock.instant();
        if (slot.getStartsAt().isBefore(now.plus(BookingService.MIN_LEAD_TIME))) {
            return 0;
        }
        UUID doctorId = slot.getDoctor().getId();
        LocalDate day = slot.getStartsAt().atZone(CLINIC_ZONE).toLocalDate();
        List<Map<String, Object>> waiting = jdbc.queryForList("""
                UPDATE slot_waitlist w SET status = 'NOTIFIED', notified_at = :now
                FROM patients p
                WHERE p.id = w.patient_id
                  AND w.doctor_id = :doctor AND w.target_date = :day AND w.status IN ('ACTIVE', 'NOTIFIED')
                RETURNING w.id, COALESCE(p.user_id, p.guardian_user_id) AS account,
                          CASE WHEN p.user_id IS NULL THEN p.full_name ELSE '' END AS family_name
                """, new MapSqlParameterSource().addValue("doctor", doctorId).addValue("day", day)
                .addValue("now", OffsetDateTime.ofInstant(now, CLINIC_ZONE)));
        String doctorName = slot.getDoctor().getUser().getFullName();
        for (Map<String, Object> w : waiting) {
            outbox.publish(Outbox.WAITLIST_SLOT_OPENED, (UUID) w.get("id"), Map.of(
                    "patientUserId", w.get("account"),
                    "forName", firstName((String) w.get("family_name")),
                    "doctorId", doctorId,
                    "doctorName", doctorName,
                    "dayLabel", DAY.format(day),
                    "scheduledAt", slot.getStartsAt()));
        }
        return waiting.size();
    }

    /** The patient booked this doctor on this day: their wait is over. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void booked(UUID doctorId, UUID patientId, Instant startsAt) {
        jdbc.update("""
                UPDATE slot_waitlist SET status = 'FULFILLED'
                WHERE doctor_id = :doctor AND patient_id = :patient AND target_date = :day
                  AND status IN ('ACTIVE', 'NOTIFIED')
                """, Map.of("doctor", doctorId, "patient", patientId,
                "day", startsAt.atZone(CLINIC_ZONE).toLocalDate()));
    }

    private static final String SELECT = """
            SELECT w.id, w.doctor_id, du.full_name AS doctor_name, d.specialization, w.patient_id,
                   COALESCE(pu.full_name, p.full_name) AS patient_name, w.target_date, w.status
            FROM slot_waitlist w
            JOIN doctors d ON d.id = w.doctor_id
            JOIN users du ON du.id = d.user_id
            JOIN patients p ON p.id = w.patient_id
            LEFT JOIN users pu ON pu.id = p.user_id
            """;

    private static final RowMapper<Entry> MAPPER = (rs, n) -> new Entry(
            rs.getObject("id", UUID.class), rs.getObject("doctor_id", UUID.class), rs.getString("doctor_name"),
            rs.getString("specialization"), rs.getObject("patient_id", UUID.class), rs.getString("patient_name"),
            rs.getObject("target_date", LocalDate.class), rs.getString("status"));

    private Entry entry(UUID id) {
        return jdbc.query(SELECT + " WHERE w.id = :id", Map.of("id", id), MAPPER).get(0);
    }

    private LocalDate today() {
        return clock.instant().atZone(CLINIC_ZONE).toLocalDate();
    }

    /** "Lalitha Nair" becomes "Lalitha", as the other family notifications name people. */
    private static String firstName(String fullName) {
        return fullName == null || fullName.isBlank() ? "" : fullName.trim().split("\\s+")[0];
    }
}
