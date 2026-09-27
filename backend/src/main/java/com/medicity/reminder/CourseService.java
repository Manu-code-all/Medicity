package com.medicity.reminder;

import com.medicity.common.NotFoundException;
import com.medicity.outbox.Outbox;
import com.medicity.patient.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Where each prescribed medicine stands: not started, being taken, running
 * out, finished. And the reminders that follow from it.
 *
 * <p>A course starts when the medicines were handed over: the latest of the
 * hospital pharmacy dispensing the prescription and a neighbourhood store's
 * collection. It ends {@code duration_days} later. Nothing here is stored:
 * it is derived each time from facts already recorded, so there is no second
 * copy of "when did she start" to fall out of step.
 *
 * <p>Two kinds of reminder, because they ask for different things:
 * <ul>
 *   <li><b>Running out</b>, for medicines taken for {@value #ONGOING_DAYS}
 *       days or more (blood pressure, diabetes, acid reflux), three days
 *       before the last dose: time to ask the stores again.</li>
 *   <li><b>Course ending</b>, for shorter courses (an antibiotic), the day
 *       before the last dose: finish it, do not stop early. No suggestion to
 *       buy more.</li>
 * </ul>
 *
 * <p>Days are counted in India time. There is no per-patient time zone; every
 * store and doctor on the platform is in India.
 */
@Service
@RequiredArgsConstructor
public class CourseService {

    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    static final int ONGOING_DAYS = 14;
    static final int REFILL_LEAD_DAYS = 3;
    /** Finished courses stay listed this long, then drop off. */
    static final int SHOW_FINISHED_DAYS = 30;

    private final JdbcTemplate jdbc;
    private final PatientRepository patientRepository;
    private final Outbox outbox;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<Course> forPatient(UUID patientUserId) {
        UUID patientId = patientRepository.findByUserId(patientUserId)
                .orElseThrow(() -> new NotFoundException("Patient profile for user", patientUserId)).getId();
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        return courses("rx.patient_id = ?", patientId).stream()
                .map(c -> c.on(today))
                .filter(c -> c.status() != Status.FINISHED || c.daysLeft() > -SHOW_FINISHED_DAYS)
                .sorted(Comparator.comparing(Course::status).thenComparing(Course::daysLeft))
                .toList();
    }

    /**
     * Publishes today's reminders. Safe to run any number of times a day: each
     * reminder's event id is derived from the medicine and the date it runs
     * out, so a second run publishes nothing new, and a later refill (a new
     * end date) earns a new reminder.
     *
     * @return reminders published by this run
     */
    @Transactional
    public int publishReminders() {
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        int published = 0;
        // Only medicines handed over in the last year can be running out now.
        for (Course c : courses("fill.started_at > ?", Timestamp.from(clock.instant().minus(Duration.ofDays(366))))) {
            Course on = c.on(today);
            if (on.startedAt() == null) {
                continue;
            }
            boolean ongoing = on.durationDays() >= ONGOING_DAYS;
            boolean due = ongoing
                    ? on.daysLeft() >= 0 && on.daysLeft() <= REFILL_LEAD_DAYS
                    : on.daysLeft() == 1;
            if (!due) {
                continue;
            }
            String type = ongoing ? Outbox.MEDICINE_RUNNING_OUT : Outbox.COURSE_ENDING;
            UUID eventId = UUID.nameUUIDFromBytes((type + ":" + on.prescriptionItemId() + ":" + on.lastDay())
                    .getBytes(StandardCharsets.UTF_8));
            Map<String, Object> payload = new HashMap<>();
            payload.put("patientUserId", on.patientUserId());
            payload.put("medicine", on.medicine() + (on.strength() == null ? "" : " " + on.strength()));
            payload.put("prescriptionId", on.prescriptionId());
            payload.put("daysLeft", on.daysLeft());
            payload.put("lastDay", on.lastDay().toString());
            if (outbox.publishOnce(type, eventId, on.prescriptionId(), payload)) {
                published++;
            }
        }
        return published;
    }

    private List<Course> courses(String where, Object... args) {
        return jdbc.query("""
                SELECT pi.id AS item_id, rx.id AS prescription_id, p.user_id AS patient_user_id,
                       m.name, m.strength, pi.frequency, pi.duration_days, rx.issued_at,
                       fill.started_at, fill.started_where
                FROM prescriptions rx
                JOIN prescription_items pi ON pi.prescription_id = rx.id
                JOIN medicines m ON m.id = pi.medicine_id
                JOIN patients p ON p.id = rx.patient_id
                LEFT JOIN LATERAL (
                    SELECT at AS started_at, place AS started_where FROM (
                        SELECT pd.dispensed_at AS at, 'the hospital pharmacy' AS place
                        FROM prescription_dispensations pd WHERE pd.prescription_id = rx.id
                        UNION ALL
                        SELECT r.collected_at, s.name
                        FROM reservations r
                        JOIN medicine_requests q ON q.id = r.request_id
                        JOIN stores s ON s.id = r.store_id
                        WHERE q.prescription_id = rx.id AND r.status = 'COLLECTED'
                    ) fills
                    ORDER BY at DESC
                    LIMIT 1
                ) fill ON TRUE
                WHERE NOT EXISTS (SELECT 1 FROM prescriptions newer WHERE newer.supersedes_id = rx.id)
                  AND %s
                """.formatted(where), (rs, i) -> new Course(
                        rs.getObject("item_id", UUID.class), rs.getObject("prescription_id", UUID.class),
                        rs.getObject("patient_user_id", UUID.class), rs.getString("name"), rs.getString("strength"),
                        rs.getString("frequency"), rs.getInt("duration_days"),
                        rs.getTimestamp("issued_at").toInstant(), ts(rs.getTimestamp("started_at")),
                        rs.getString("started_where"), null, null, 0, false, Status.NOT_STARTED),
                args);
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    public enum Status { RUNNING_OUT, TAKING, NOT_STARTED, FINISHED }

    public record Course(
            UUID prescriptionItemId,
            UUID prescriptionId,
            UUID patientUserId,
            String medicine,
            String strength,
            String frequency,
            int durationDays,
            Instant issuedAt,
            /** When the medicines were handed over; null if not yet. */
            Instant startedAt,
            String startedWhere,
            LocalDate firstDay,
            /** The day of the last dose. */
            LocalDate lastDay,
            /** Days from today to the last day: 0 on the last day, negative once finished. */
            long daysLeft,
            /** Taken long-term, so running out means asking again. */
            boolean ongoing,
            Status status
    ) {
        Course on(LocalDate today) {
            boolean isOngoing = durationDays >= ONGOING_DAYS;
            if (startedAt == null) {
                return new Course(prescriptionItemId, prescriptionId, patientUserId, medicine, strength, frequency,
                        durationDays, issuedAt, null, null, null, null, 0, isOngoing, Status.NOT_STARTED);
            }
            LocalDate first = startedAt.atZone(ZONE).toLocalDate();
            LocalDate last = first.plusDays(durationDays - 1L);
            long left = ChronoUnit.DAYS.between(today, last);
            Status status = left < 0 ? Status.FINISHED
                    : isOngoing && left <= REFILL_LEAD_DAYS ? Status.RUNNING_OUT
                    : Status.TAKING;
            return new Course(prescriptionItemId, prescriptionId, patientUserId, medicine, strength, frequency,
                    durationDays, issuedAt, startedAt, startedWhere, first, last, left, isOngoing, status);
        }
    }
}
