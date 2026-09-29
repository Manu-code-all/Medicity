package com.medicity.doctor;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Is the doctor running on time? An estimate from what the system knows:
 * visits are closed (seen or missed) as the doctor finishes them, so a visit
 * still open after its slot has ended means the doctor has not got past it.
 * The delay is how long ago the oldest such visit should have ended.
 *
 * <p>A visit still inside its own slot is in progress, not late. A visit
 * that ended more than {@link #FORGOTTEN_AFTER} ago and was never closed is
 * taken as forgotten (the nightly job closes those as missed) rather than as
 * a doctor running hours behind.
 */
@Service
@RequiredArgsConstructor
public class LiveStatusService {

    /** Behind by more than this reads as late; less is within a clinic's normal drift. */
    static final int ON_TIME_MINUTES = 10;
    static final Duration FORGOTTEN_AFTER = Duration.ofMinutes(90);

    private final NamedParameterJdbcTemplate jdbc;
    private final DoctorRepository doctors;
    private final Clock clock;

    public enum State { ON_TIME, RUNNING_LATE }

    public record Status(State state, int delayMinutes, boolean visitInProgress, Instant asOf) {}

    public Status status(UUID doctorId) {
        doctors.findById(doctorId).filter(Doctor::isVerified)
                .orElseThrow(() -> new com.medicity.common.NotFoundException("Doctor", doctorId));
        Instant now = clock.instant();
        var params = Map.of("doctor", doctorId,
                "now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                "forgotten", OffsetDateTime.ofInstant(now.minus(FORGOTTEN_AFTER), ZoneOffset.UTC));
        record Row(OffsetDateTime overdueEnd, boolean inProgress) {}
        Row row = jdbc.queryForObject("""
                SELECT min(s.ends_at) FILTER (WHERE s.ends_at <= :now) AS overdue_end,
                       count(*) > 0 AS in_progress
                FROM appointments a JOIN appointment_slots s ON s.id = a.slot_id
                WHERE s.doctor_id = :doctor
                  AND a.status = 'BOOKED'
                  AND s.starts_at <= :now
                  AND s.ends_at > :forgotten
                """, params, (rs, n) -> new Row(rs.getObject("overdue_end", OffsetDateTime.class),
                rs.getBoolean("in_progress")));
        int delay = row.overdueEnd() == null ? 0 : (int) Duration.between(row.overdueEnd().toInstant(), now).toMinutes();
        boolean inProgress = row.inProgress();
        return new Status(delay > ON_TIME_MINUTES ? State.RUNNING_LATE : State.ON_TIME, delay, inProgress, now);
    }
}
