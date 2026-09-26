package com.medicity.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicity.outbox.Outbox;
import com.medicity.outbox.OutboxConsumer;
import com.medicity.outbox.OutboxEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Turns outbox events into in-app notifications.
 *
 * <p>Idempotent by construction: {@code ON CONFLICT (event_id, user_id) DO
 * NOTHING} means an event delivered twice (the relay crashed after this ran
 * but before marking the event published) leaves one notification, not two.
 *
 * <p>Email or SMS would be further consumers of the same events. They are not
 * built: there is no provider configured, and a stub that pretends to send
 * would be worse than none.
 */
@Component
public class NotificationConsumer implements OutboxConsumer {

    private final JdbcTemplate jdbc;

    public NotificationConsumer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean handle(OutboxEvent event) {
        JsonNode p = event.payload();
        switch (event.type()) {
            case Outbox.APPOINTMENT_BOOKED -> notify(event, uuid(p, "patientUserId"),
                    "Appointment confirmed", "With " + p.path("doctorName").asText() + ".",
                    "/portal/visits", instant(p, "scheduledAt"));

            case Outbox.APPOINTMENT_CANCELLED -> notify(event, uuid(p, "doctorUserId"),
                    "Visit cancelled",
                    p.path("patientName").asText() + " cancelled"
                            + (p.path("late").asBoolean() ? " at short notice." : "."),
                    "/doctor", instant(p, "scheduledAt"));

            case Outbox.PRESCRIPTION_ISSUED -> notify(event, uuid(p, "patientUserId"),
                    "New prescription", p.path("doctorName").asText() + " prescribed for "
                            + p.path("diagnosis").asText() + ".",
                    "/portal/prescriptions", null);

            case Outbox.PRESCRIPTION_CORRECTED -> {
                boolean dispensed = p.path("originalDispensed").asBoolean();
                notify(event, uuid(p, "patientUserId"), "Prescription corrected",
                        p.path("doctorName").asText() + " corrected your prescription for "
                                + p.path("diagnosis").asText() + "."
                                + (dispensed ? " You may already have medicines from the earlier version:"
                                + " follow the new one, and ask the pharmacy if unsure." : ""),
                        "/portal/prescriptions", null);
                if (dispensed) {
                    // The pharmacy handed out the superseded version. ADMIN
                    // stands in for a pharmacist role, which does not exist yet.
                    for (UUID admin : activeAdmins()) {
                        notify(event, admin, "Dispensed prescription was corrected",
                                "A prescription for " + p.path("patientName").asText()
                                        + " was corrected after it was dispensed. Check what the patient holds.",
                                null, null);
                    }
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void notify(OutboxEvent event, UUID userId, String title, String body, String link, Instant occursAt) {
        jdbc.update("""
                INSERT INTO notifications (user_id, event_id, kind, title, body, link, occurs_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id, user_id) DO NOTHING
                """, userId, event.eventId(), event.type(), title, body, link,
                occursAt == null ? null : Timestamp.from(occursAt));
    }

    private List<UUID> activeAdmins() {
        return jdbc.queryForList("SELECT id FROM users WHERE role = 'ADMIN' AND enabled", UUID.class);
    }

    private static UUID uuid(JsonNode payload, String field) {
        return UUID.fromString(payload.path(field).asText());
    }

    private static Instant instant(JsonNode payload, String field) {
        return Instant.parse(payload.path(field).asText());
    }
}
