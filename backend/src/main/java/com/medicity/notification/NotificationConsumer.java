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
                    "Appointment confirmed", forWhom(p, "With ") + p.path("doctorName").asText() + ".",
                    "/portal/visits", instant(p, "scheduledAt"));

            case Outbox.APPOINTMENT_CANCELLED -> notify(event, uuid(p, "doctorUserId"),
                    "Visit cancelled",
                    p.path("patientName").asText() + " cancelled"
                            + (p.path("late").asBoolean() ? " at short notice." : "."),
                    "/doctor", instant(p, "scheduledAt"));

            case Outbox.APPOINTMENT_RESCHEDULED -> notify(event, uuid(p, "doctorUserId"),
                    "Visit moved",
                    p.path("patientName").asText() + " moved their visit from "
                            + p.path("fromLabel").asText() + ".",
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
            case Outbox.STORE_REGISTERED -> {
                for (UUID admin : activeAdmins()) {
                    notify(event, admin, "Store awaiting verification",
                            p.path("storeName").asText() + ", " + p.path("city").asText()
                                    + ". Check the drug licence before it receives patients' questions.",
                            "/admin/stores", null);
                }
            }

            case Outbox.STORE_VERIFIED -> notify(event, uuid(p, "ownerUserId"), "Your store is verified",
                    p.path("storeName").asText() + " will now receive questions from patients nearby.",
                    "/store", null);

            case Outbox.DOCTOR_REGISTERED -> {
                for (UUID admin : activeAdmins()) {
                    notify(event, admin, "Doctor awaiting verification",
                            p.path("doctorName").asText() + ", " + p.path("specialization").asText() + ". Check "
                                    + p.path("registrationNumber").asText() + " with the "
                                    + p.path("medicalCouncil").asText() + " before patients can book.",
                            "/admin/doctors", null);
                }
            }

            case Outbox.DOCTOR_VERIFIED -> notify(event, uuid(p, "doctorUserId"), "You are verified",
                    "Patients can now find and book you in the hours you have set.", "/doctor/hours", null);

            case Outbox.MEDICINE_REQUEST_CREATED -> {
                int medicines = p.path("medicines").asInt();
                for (JsonNode owner : p.path("storeOwnerUserIds")) {
                    notify(event, UUID.fromString(owner.asText()), "A patient nearby is asking",
                            medicines + (medicines == 1 ? " medicine" : " medicines") + " on a prescription from "
                                    + p.path("doctorName").asText() + ". Do you have them?",
                            "/store/requests/" + event.aggregateId(), null);
                }
            }

            case Outbox.STORE_ANSWERED -> {
                int available = p.path("available").asInt();
                int asked = p.path("asked").asInt();
                String summary = p.path("complete").asBoolean() ? "Has everything you asked for."
                        : available == 0 ? "Has none of your medicines."
                        : "Has " + available + " of your " + asked + " medicines.";
                notify(event, uuid(p, "patientUserId"), p.path("storeName").asText() + " answered", summary,
                        "/portal/requests/" + event.aggregateId(), null);
            }

            case Outbox.RESERVATION_MADE -> notify(event, uuid(p, "storeOwnerUserId"),
                    "Keep aside for " + p.path("patientName").asText(),
                    p.path("medicines").asInt() + (p.path("medicines").asInt() == 1 ? " medicine" : " medicines")
                            + " reserved. The patient will show a pick-up code.",
                    "/store/reservations", instant(p, "expiresAt"));

            case Outbox.RESERVATION_CANCELLED -> notify(event, uuid(p, "storeOwnerUserId"), "Reservation cancelled",
                    p.path("patientName").asText() + " no longer needs the medicines. They can go back on the shelf.",
                    "/store/reservations", null);

            case Outbox.RESERVATION_EXPIRED -> {
                notify(event, uuid(p, "patientUserId"), "Your reservation expired",
                        p.path("storeName").asText() + " has put the medicines back. You can reserve again.",
                        "/portal/requests", null);
                notify(event, uuid(p, "storeOwnerUserId"), "Reservation not collected",
                        p.path("patientName").asText() + " did not come. The medicines can go back on the shelf.",
                        "/store/reservations", null);
            }

            case Outbox.RESERVATION_COLLECTED -> notify(event, uuid(p, "patientUserId"),
                    "Collected at " + p.path("storeName").asText(), "Your medicines were handed over.",
                    "/portal/prescriptions", null);

            case Outbox.MEDICINE_RUNNING_OUT -> {
                int left = p.path("daysLeft").asInt();
                String when = left == 0 ? "today" : left == 1 ? "tomorrow" : "in " + left + " days";
                notify(event, uuid(p, "patientUserId"), possessive(p) + p.path("medicine").asText() + " runs out " + when,
                        "Ask the chemists near you again? One tap sends your prescription to all of them.",
                        "/portal/prescriptions?ask=" + p.path("prescriptionId").asText(), null);
            }

            case Outbox.COURSE_ENDING -> notify(event, uuid(p, "patientUserId"),
                    "Last day of " + possessive(p) + p.path("medicine").asText() + " tomorrow",
                    "Finish the course, even if you feel better.", "/portal/medicines", null);

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

    /** "With Dr. Rao." for the account holder; "For Aarav, with Dr. Rao." for a family member. */
    private static String forWhom(JsonNode payload, String ownPrefix) {
        String name = payload.path("forName").asText("");
        return name.isEmpty() ? ownPrefix : "For " + name + ", " + ownPrefix.toLowerCase();
    }

    /** "" for the account holder; "Lalitha's " for a family member. */
    private static String possessive(JsonNode payload) {
        String name = payload.path("forName").asText("");
        return name.isEmpty() ? "" : name + "'s ";
    }

    private static UUID uuid(JsonNode payload, String field) {
        return UUID.fromString(payload.path(field).asText());
    }

    private static Instant instant(JsonNode payload, String field) {
        return Instant.parse(payload.path(field).asText());
    }
}
