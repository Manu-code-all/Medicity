package com.medicity.scheduling;

import com.medicity.notification.email.EmailSender;
import com.medicity.outbox.Outbox;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Finds booked visits that start within the next 24 hours and queues one
 * reminder for each, to cut no-shows.
 *
 * <p><b>Once per visit, however often it runs.</b> The event id is derived
 * from the visit, so a second run (or a second instance) that sees the same
 * visit writes nothing ({@link Outbox#publishOnce}). A rescheduled visit is a
 * new appointment row and so earns its own reminder; the old one is cancelled
 * and no longer matches.
 *
 * <p><b>Booked at least a day ahead.</b> A visit booked this morning for this
 * evening is already on the patient's mind and the confirmation email is hours
 * old; reminding them at once would be noise. Because the rule is "booked at
 * least 24 hours before it starts" rather than "starts in exactly 24 hours",
 * a job run missed (the instance was down) is made up by the next one, up to
 * the visit itself.
 *
 * <p>With no email provider there is nobody to remind, so nothing is written.
 */
@Service
public class AppointmentReminderService {

    static final Duration LEAD = Duration.ofHours(24);

    private final JdbcTemplate jdbc;
    private final Outbox outbox;
    private final EmailSender email;
    private final Clock clock;

    public AppointmentReminderService(JdbcTemplate jdbc, Outbox outbox, EmailSender email, Clock clock) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.email = email;
        this.clock = clock;
    }

    private record Due(UUID appointmentId, UUID patientUserId, String forName, String doctorName, Instant at) {}

    /** @return how many reminders were queued by this call. */
    @Transactional
    public int publishDue() {
        if (!email.available()) {
            return 0;
        }
        Instant now = clock.instant();
        List<Due> due = jdbc.query("""
                SELECT a.id, coalesce(p.user_id, p.guardian_user_id) AS patient_user_id,
                       CASE WHEN p.guardian_user_id IS NULL THEN '' ELSE split_part(p.full_name, ' ', 1) END AS for_name,
                       du.full_name AS doctor_name, a.scheduled_at
                FROM appointments a
                JOIN appointment_slots s ON s.id = a.slot_id
                JOIN patients p ON p.id = a.patient_id
                JOIN doctors d ON d.id = s.doctor_id
                JOIN users du ON du.id = d.user_id
                WHERE a.status = 'BOOKED'
                  AND a.scheduled_at > ? AND a.scheduled_at <= ?
                  AND a.created_at <= a.scheduled_at - interval '24 hours'
                """, (rs, i) -> new Due(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getTimestamp(5).toInstant()),
                Timestamp.from(now), Timestamp.from(now.plus(LEAD)));
        int queued = 0;
        for (Due d : due) {
            UUID eventId = UUID.nameUUIDFromBytes(("reminder:" + d.appointmentId()).getBytes(StandardCharsets.UTF_8));
            if (outbox.publishOnce(Outbox.APPOINTMENT_REMINDER, eventId, d.appointmentId(), Map.of(
                    "patientUserId", d.patientUserId(),
                    "forName", d.forName(),
                    "doctorName", d.doctorName(),
                    "scheduledAt", d.at()))) {
                queued++;
            }
        }
        return queued;
    }
}
