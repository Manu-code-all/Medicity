package com.medicity.doctor;

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
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * A doctor's weekly hours, and the bookable slots made from them.
 *
 * <p>Hours are one window per weekday in the clinic's time zone. Saving them
 * replaces the doctor's future slots that nobody has ever booked, then makes
 * slots for the next four weeks; a slot someone booked is never touched, and
 * a new slot that would overlap it is skipped by the database's own
 * no-overlap rule. A nightly job tops the four weeks up as days pass.
 */
@Service
@RequiredArgsConstructor
public class DoctorHoursService {

    static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Kolkata");
    static final int WEEKS_AHEAD = 4;

    private final JdbcTemplate jdbc;
    private final DoctorRepository doctors;
    private final Clock clock;

    public record Window(int weekday, LocalTime startsAt, LocalTime endsAt, int slotMinutes) {}

    @Transactional(readOnly = true)
    public List<Window> hours(UUID doctorUserId) {
        return read(requireDoctor(doctorUserId));
    }

    /** @return how many new slots were opened */
    @Transactional
    public int replace(UUID doctorUserId, List<Window> windows) {
        UUID doctorId = requireDoctor(doctorUserId);
        var weekdays = new HashSet<Integer>();
        for (Window w : windows) {
            if (!weekdays.add(w.weekday())) {
                throw new ValidationException("DUPLICATE_DAY", "Give each day at most one window");
            }
            if (!w.endsAt().isAfter(w.startsAt())) {
                throw new ValidationException("INVALID_HOURS", "Each day must end after it starts");
            }
            if (Duration.between(w.startsAt(), w.endsAt()).toMinutes() < w.slotMinutes()) {
                throw new ValidationException("INVALID_HOURS", "Each day must fit at least one appointment");
            }
        }
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
        LocalDate first = LocalDate.now(clock.withZone(CLINIC_ZONE)).plusDays(1);
        List<Instant[]> slots = new ArrayList<>();
        for (int d = 0; d < WEEKS_AHEAD * 7; d++) {
            LocalDate day = first.plusDays(d);
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
                WHERE doctor_id = ? ORDER BY weekday
                """, (rs, i) -> new Window(rs.getInt(1), rs.getTime(2).toLocalTime(), rs.getTime(3).toLocalTime(),
                rs.getInt(4)), doctorId);
    }

    private UUID requireDoctor(UUID doctorUserId) {
        return doctors.findByUserId(doctorUserId)
                .orElseThrow(() -> new NotFoundException("Doctor for user", doctorUserId))
                .getId();
    }
}
