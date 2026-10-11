package com.medicity.notification.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicity.audit.AuditLog;
import com.medicity.outbox.Outbox;
import com.medicity.outbox.OutboxConsumer;
import com.medicity.outbox.OutboxEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Emails people about what the in-app notifications tell them, as a second
 * consumer of the same outbox events.
 *
 * <p><b>Two steps, so a failure stays small.</b> A domain event (a booking, a
 * corrected prescription) is first <i>fanned out</i>: for each person to be
 * emailed, one {@link Outbox#EMAIL_SEND} event is written, carrying only what
 * the email needs. That event is then <i>delivered</i> by this same consumer,
 * on its own turn. Sending inline would let one provider outage fail the
 * original event, which would roll back its in-app notification and retry
 * both together; and a leave day affecting twenty patients would resend to
 * the first nineteen whenever the twentieth failed. As separate events, each
 * email succeeds, retries and gives up alone, using the relay's own backoff
 * (5 s doubling to an hour, ten attempts, then set aside).
 *
 * <p><b>Never on the request path.</b> Nothing here runs in the request that
 * caused the event: that request only wrote an outbox row, as before.
 *
 * <p><b>No provider, no emails.</b> With no {@code RESEND_API_KEY} the fan-out
 * writes nothing, so the outbox does not fill with events that cannot be sent.
 *
 * <p><b>At least once.</b> An email cannot be rolled back. If the process dies
 * after the provider accepted a message but before the event is marked
 * published, the event is delivered again and the person gets a second copy.
 * The window is a few milliseconds; closing it needs a provider-side
 * idempotency key, which is not used.
 */
@Component
@Slf4j
public class EmailConsumer implements OutboxConsumer {

    private static final Map<String, String> TEMPLATE_BY_EVENT = Map.of(
            Outbox.APPOINTMENT_BOOKED, EmailTemplates.BOOKED,
            Outbox.APPOINTMENT_CANCELLED, EmailTemplates.CANCELLED,
            Outbox.APPOINTMENT_RESCHEDULED, EmailTemplates.RESCHEDULED,
            Outbox.PRESCRIPTION_ISSUED, EmailTemplates.PRESCRIPTION_ISSUED,
            Outbox.PRESCRIPTION_CORRECTED, EmailTemplates.PRESCRIPTION_CORRECTED,
            Outbox.DOCTOR_LEAVE_VISIT_AFFECTED, EmailTemplates.DOCTOR_AWAY,
            Outbox.APPOINTMENT_REMINDER, EmailTemplates.REMINDER);

    private static final int ERROR_MAX = 300;

    private final JdbcTemplate jdbc;
    private final Outbox outbox;
    private final EmailSender sender;
    private final EmailPreferences preferences;
    private final AuditLog auditLog;
    private final String appUrl;
    private final String apiUrl;

    public EmailConsumer(JdbcTemplate jdbc, Outbox outbox, EmailSender sender, EmailPreferences preferences,
                         AuditLog auditLog,
                         @Value("${medicity.email.app-url:}") String appUrl,
                         @Value("${medicity.email.api-url:}") String apiUrl) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.sender = sender;
        this.preferences = preferences;
        this.auditLog = auditLog;
        this.appUrl = strip(appUrl);
        this.apiUrl = strip(apiUrl);
    }

    @Override
    public boolean handle(OutboxEvent event) {
        if (Outbox.EMAIL_SEND.equals(event.type())) {
            deliver(event);
            return true;
        }
        String template = TEMPLATE_BY_EVENT.get(event.type());
        if (template == null) {
            return false;
        }
        fanOut(event, template);
        return true;
    }

    // --- step 1: who is to be emailed -------------------------------------------

    private void fanOut(OutboxEvent source, String template) {
        if (!sender.available()) {
            return;
        }
        JsonNode p = source.payload();
        String userText = p.path("patientUserId").asText("");
        if (userText.isEmpty()) {
            return;     // an event from before the payload carried the recipient
        }
        UUID userId = UUID.fromString(userText);
        if (!preferences.enabled(userId)) {
            return;
        }
        if (Outbox.APPOINTMENT_BOOKED.equals(source.type()) && isReschedule(source.aggregateId())) {
            // Moving a visit books the new time through the ordinary path, which
            // publishes "booked" as well as "moved". One email says it.
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", userId);
        payload.put("template", template);
        if (isVisitTemplate(template)) {
            // Only what the visit emails say. Prescription emails carry nothing
            // from the prescription at all, so none of it is stored here either.
            payload.put("doctorName", p.path("doctorName").asText(""));
            payload.put("forName", p.path("forName").asText(""));
            payload.put("scheduledAt", p.path("scheduledAt").asText(""));
        }
        UUID emailEventId = UUID.nameUUIDFromBytes(
                ("email:" + source.eventId() + ":" + userId).getBytes(StandardCharsets.UTF_8));
        outbox.publishOnce(Outbox.EMAIL_SEND, emailEventId, source.aggregateId(), payload);
    }

    private boolean isReschedule(UUID appointmentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT coalesce(bool_or(rescheduled_from IS NOT NULL), false) FROM appointments WHERE id = ?",
                Boolean.class, appointmentId));
    }

    private static boolean isVisitTemplate(String template) {
        return !template.startsWith("PRESCRIPTION_");
    }

    // --- step 2: one email ------------------------------------------------------

    private void deliver(OutboxEvent event) {
        JsonNode p = event.payload();
        UUID userId = UUID.fromString(p.path("userId").asText());
        String template = p.path("template").asText();

        // Checked again: the person may have opted out since the event was written,
        // or the provider been switched off. Skipping is a success, not a failure.
        if (!sender.available() || !preferences.enabled(userId)) {
            return;
        }
        List<String> address = jdbc.queryForList(
                "SELECT email FROM users WHERE id = ? AND enabled", String.class, userId);
        EmailTemplates.Body body = EmailTemplates.body(template, p);
        if (address.isEmpty() || body == null) {
            log.warn("Email event {} skipped: no active account or unknown template {}", event.eventId(), template);
            return;
        }

        String unsubscribeUrl = apiUrl.isEmpty() ? "" : apiUrl + "/api/v1/notifications/email/unsubscribe?u="
                + userId + "&t=" + preferences.token(userId);
        Map<String, String> headers = unsubscribeUrl.isEmpty() ? Map.of() : Map.of(
                "List-Unsubscribe", "<" + unsubscribeUrl + ">",
                "List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        EmailSender.Message message = new EmailSender.Message(address.get(0), body.subject(),
                EmailTemplates.text(body, appUrl, unsubscribeUrl),
                EmailTemplates.html(body, appUrl, unsubscribeUrl), headers);

        try {
            sender.send(message);
        } catch (RuntimeException e) {
            // Audited in its own transaction: this one is about to roll back, and
            // the failure is exactly what must survive. The address is not
            // recorded, only the account.
            String reason = String.valueOf(e.getMessage());
            reason = reason.length() > ERROR_MAX ? reason.substring(0, ERROR_MAX) : reason;
            log.warn("Email '{}' to user {} failed, the outbox will retry: {}", template, userId, reason);
            auditLog.recordIndependentlyAs(null, "SYSTEM", "EMAIL_SEND_FAILED", "USER", userId,
                    AuditLog.Outcome.ERROR, Map.of("template", template, "error", reason));
            throw e;
        }
    }

    private static String strip(String url) {
        String trimmed = url == null ? "" : url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
