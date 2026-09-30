package com.medicity.doctor;

import com.medicity.audit.AuditLog;
import com.medicity.common.NotFoundException;
import com.medicity.common.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Time;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A doctor's weekly hours, and the bookable slots made from them.
 *
 * <p>Hours are up to three sessions per weekday in the clinic's time zone
 * (a morning clinic, a lunch break, an evening clinic). Saving them
 * replaces the doctor's future slots that nobody has ever booked, then makes
 * slots for the next four weeks; a slot someone booked is never touched, and
 * a new slot that would overlap it is skipped by the database's own
 * no-overlap rule. A nightly job tops the four weeks up as days pass.
 *
 * <p>Leave days get no slots. Marking one removes that day's slots nobody
 * booked; visits already booked are kept and counted, so the doctor can
 * decide what to tell those patients.
 */
@Service
@RequiredArgsConstructor
public class DoctorHoursService {

    static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Kolkata");
    static final int WEEKS_AHEAD = 4;
    static final int MAX_SESSIONS_PER_DAY = 3;
    static final int LEAVE_DAYS_AHEAD = 365;
    private static final String[] DAY_NAMES = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};

    private final JdbcTemplate jdbc;
    private final DoctorRepository doctors;
    private final Clock clock;
    private final AuditLog auditLog;

    public record Window(int weekday, LocalTime startsAt, LocalTime endsAt, int slotMinutes) {}

    /** A day off, and how many visits were already booked on it. */
    public record Leave(LocalDate day, String note, int bookedVisits) {}

    @Transactional(readOnly = true)
    public List<Window> hours(UUID doctorUserId) {
        return read(requireDoctor(doctorUserId));
    }

    /** @return how many new slots were opened */
    @Transactional
    public int replace(UUID doctorUserId, List<Window> windows) {
        UUID doctorId = requireDoctor(doctorUserId);
        for (Window w : windows) {
            if (!w.endsAt().isAfter(w.startsAt())) {
                throw new ValidationException("INVALID_HOURS", "Each day must end after it starts");
            }
            if (Duration.between(w.startsAt(), w.endsAt()).toMinutes() < w.slotMinutes()) {
                throw new ValidationException("INVALID_HOURS", "Each day must fit at least one appointment");
            }
        }
        Map<Integer, List<Window>> byDay = windows.stream().collect(Collectors.groupingBy(Window::weekday,
                TreeMap::new, Collectors.toList()));
        byDay.forEach((weekday, sessions) -> {
            String name = DAY_NAMES[weekday - 1];
            if (sessions.size() > MAX_SESSIONS_PER_DAY) {
                throw new ValidationException("TOO_MANY_SESSIONS",
                        "%s has more than %d sessions".formatted(name, MAX_SESSIONS_PER_DAY));
            }
            sessions.sort(Comparator.comparing(Window::startsAt));
            for (int i = 1; i < sessions.size(); i++) {
                if (sessions.get(i).startsAt().isBefore(sessions.get(i - 1).endsAt())) {
                    throw new ValidationException("SESSIONS_OVERLAP", "Two of the sessions on " + name + " overlap");
                }
            }
        });
        jdbc.update("DELETE FROM doctor_hours WHERE doctor_id = ?", doctorId);
        jdbc.batchUpdate("""
                INSERT INTO doctor_hours (doctor_id, weekday, starts_at, ends_at, slot_minutes) VALUES (?, ?, ?, ?, ?)
                """, windows, windows.size(), (ps, w) -> {
            ps.setObject(1, doctorId);
            ps.setInt(2, w.weekday());
            ps.setTime(3, Time.valueOf(w.startsAt()));
            ps.setTime(4, Time.valueOf(w.endsAt()));
            ps.setInt(5, w.slotMinutes());
        });

        // Future slots nobody ever booked go; anything with an appointment
        // row, even a cancelled one, stays as the record of that visit.
        jdbc.update("""
                DELETE FROM appointment_slots s
                WHERE s.doctor_id = ? AND s.starts_at > ? AND s.status = 'OPEN'
                  AND NOT EXISTS (SELECT 1 FROM appointments a WHERE a.slot_id = s.id)
                """, doctorId, Timestamp.from(clock.instant()));
        return open(doctorId, windows);
    }

    /** Upcoming days off, soonest first, each with the visits already booked on it. */
    @Transactional(readOnly = true)
    public List<Leave> leave(UUID doctorUserId) {
        UUID doctorId = requireDoctor(doctorUserId);
        return jdbc.query("""
                SELECT l.day, l.note,
                       (SELECT count(*) FROM appointments a JOIN appointment_slots s ON s.id = a.slot_id
                        WHERE s.doctor_id = l.doctor_id AND a.status = 'BOOKED'
                          AND s.starts_at >= (l.day::timestamp AT TIME ZONE 'Asia/Kolkata')
                          AND s.starts_at < ((l.day + 1)::timestamp AT TIME ZONE 'Asia/Kolkata')) AS booked
                FROM doctor_leave l
                WHERE l.doctor_id = ? AND l.day >= ?
                ORDER BY l.day
                """, (rs, i) -> new Leave(rs.getObject("day", LocalDate.class), rs.getString("note"), rs.getInt("booked")),
                doctorId, today());
    }

    /**
     * Marks a day off: no slots are opened on it, and its open slots nobody
     * booked are removed. Booked visits stay; the answer says how many.
     */
    @Transactional
    public Leave addLeave(UUID doctorUserId, LocalDate day, String note) {
        UUID doctorId = requireDoctor(doctorUserId);
        LocalDate today = today();
        if (day.isBefore(today)) {
            throw new ValidationException("LEAVE_IN_PAST", "Choose today or a later day");
        }
        if (day.isAfter(today.plusDays(LEAVE_DAYS_AHEAD))) {
            throw new ValidationException("LEAVE_TOO_FAR", "Leave can be marked up to a year ahead");
        }
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        jdbc.update("""
                INSERT INTO doctor_leave (doctor_id, day, note) VALUES (?, ?, ?)
                ON CONFLICT (doctor_id, day) DO UPDATE SET note = EXCLUDED.note
                """, doctorId, day, cleanNote);
        int removed = jdbc.update("""
                DELETE FROM appointment_slots s
                WHERE s.doctor_id = ? AND s.status = 'OPEN' AND s.starts_at > ?
                  AND s.starts_at >= ? AND s.starts_at < ?
                  AND NOT EXISTS (SELECT 1 FROM appointments a WHERE a.slot_id = s.id)
                """, doctorId, Timestamp.from(clock.instant()),
                Timestamp.from(day.atStartOfDay(CLINIC_ZONE).toInstant()),
                Timestamp.from(day.plusDays(1).atStartOfDay(CLINIC_ZONE).toInstant()));
        auditLog.recordChange("DOCTOR_LEAVE_ADDED", "DOCTOR", doctorId,
                Map.of("day", day.toString(), "slotsRemoved", removed));
        return leave(doctorUserId).stream().filter(l -> l.day().equals(day)).findFirst().orElseThrow();
    }

    /** Back at work that day: its slots open again. */
    @Transactional
    public int removeLeave(UUID doctorUserId, LocalDate day) {
        UUID doctorId = requireDoctor(doctorUserId);
        int deleted = jdbc.update("DELETE FROM doctor_leave WHERE doctor_id = ? AND day = ?", doctorId, day);
        if (deleted == 0) {
            throw new NotFoundException("Leave on", day);
        }
        auditLog.recordChange("DOCTOR_LEAVE_REMOVED", "DOCTOR", doctorId, Map.of("day", day.toString()));
        return open(doctorId, read(doctorId));
    }

    /** Tops every doctor with hours up to four weeks ahead. Called nightly. */
    @Transactional
    public int rollForward() {
        List<UUID> withHours = jdbc.queryForList("SELECT DISTINCT doctor_id FROM doctor_hours", UUID.class);
        int opened = 0;
        for (UUID doctorId : withHours) {
            opened += open(doctorId, read(doctorId));
        }
        return opened;
    }

    /**
     * Inserts the slots the windows describe from tomorrow for four weeks.
     * ON CONFLICT DO NOTHING lets the exclusion constraint skip any that
     * already exist or overlap a kept booking, so this is safe to repeat.
     */
    private int open(UUID doctorId, List<Window> windows) {
        LocalDate first = today().plusDays(1);
        Set<LocalDate> away = new HashSet<>(jdbc.queryForList(
                "SELECT day FROM doctor_leave WHERE doctor_id = ? AND day >= ?", LocalDate.class, doctorId, first));
        List<Instant[]> slots = new ArrayList<>();
        for (int d = 0; d < WEEKS_AHEAD * 7; d++) {
            LocalDate day = first.plusDays(d);
            if (away.contains(day)) {
                continue;
            }
            for (Window w : windows) {
                if (day.getDayOfWeek() != DayOfWeek.of(w.weekday())) {
                    continue;
                }
                // In minutes of the day, so a window ending near midnight cannot wrap.
                int end = w.endsAt().toSecondOfDay() / 60;
                for (int m = w.startsAt().toSecondOfDay() / 60; m + w.slotMinutes() <= end; m += w.slotMinutes()) {
                    ZonedDateTime start = day.atTime(LocalTime.ofSecondOfDay(m * 60L)).atZone(CLINIC_ZONE);
                    slots.add(new Instant[]{start.toInstant(), start.plusMinutes(w.slotMinutes()).toInstant()});
                }
            }
        }
        int opened = 0;
        for (int[] batch : jdbc.batchUpdate("""
                INSERT INTO appointment_slots (doctor_id, starts_at, ends_at, status)
                VALUES (?, ?, ?, 'OPEN')
                ON CONFLICT DO NOTHING
                """, slots, 500, (ps, s) -> {
            ps.setObject(1, doctorId);
            ps.setTimestamp(2, Timestamp.from(s[0]));
            ps.setTimestamp(3, Timestamp.from(s[1]));
        })) {
            for (int n : batch) {
                opened += Math.max(n, 0);
            }
        }
        return opened;
    }

    private List<Window> read(UUID doctorId) {
        return jdbc.query("""
                SELECT weekday, starts_at, ends_at, slot_minutes FROM doctor_hours
                WHERE doctor_id = ? ORDER BY weekday, starts_at
                """, (rs, i) -> new Window(rs.getInt(1), rs.getTime(2).toLocalTime(), rs.getTime(3).toLocalTime(),
                rs.getInt(4)), doctorId);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(CLINIC_ZONE));
    }

    private UUID requireDoctor(UUID doctorUserId) {
        return doctors.findByUserId(doctorUserId)
                .orElseThrow(() -> new NotFoundException("Doctor for user", doctorUserId))
                .getId();
    }
}
