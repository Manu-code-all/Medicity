package com.medicity.notification.email;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The words of each email.
 *
 * <p><b>Privacy.</b> An email crosses networks and sits in inboxes that the
 * app's access control does not protect, so it says as little as will do.
 * Visit emails name the doctor and the time, because the reader needs both to
 * act. Prescription emails name neither the doctor, the diagnosis nor any
 * medicine: "you have a new prescription, sign in to view". Subjects are
 * generic, since they appear on lock screens. A doctor's leave note is never
 * included.
 */
final class EmailTemplates {

    static final String BOOKED = "BOOKED";
    static final String CANCELLED = "CANCELLED";
    static final String RESCHEDULED = "RESCHEDULED";
    static final String PRESCRIPTION_ISSUED = "PRESCRIPTION_ISSUED";
    static final String PRESCRIPTION_CORRECTED = "PRESCRIPTION_CORRECTED";
    static final String DOCTOR_AWAY = "DOCTOR_AWAY";
    static final String REMINDER = "REMINDER";

    private static final DateTimeFormatter INDIA_TIME =
            DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH).withZone(ZoneId.of("Asia/Kolkata"));

    /** What the email says, before the footer. */
    record Body(String subject, String text) {}

    private EmailTemplates() {}

    /** @return null for a template this version does not know (an event from a newer deploy). */
    static Body body(String template, JsonNode data) {
        String doctor = data.path("doctorName").asText("your doctor");
        String when = timeOf(data);
        String own = data.path("forName").asText("").isEmpty() ? "your" : data.path("forName").asText() + "'s";
        String forWhom = Character.toUpperCase(own.charAt(0)) + own.substring(1);
        return switch (template) {
            case BOOKED -> new Body("Your Medicity appointment is confirmed",
                    forWhom + " appointment with " + doctor + " is confirmed for " + when + ".");
            case CANCELLED -> new Body("Your Medicity appointment was cancelled",
                    forWhom + " appointment with " + doctor + " on " + when + " was cancelled.");
            case RESCHEDULED -> new Body("Your Medicity appointment was moved",
                    forWhom + " appointment with " + doctor + " is now on " + when + ".");
            case PRESCRIPTION_ISSUED -> new Body("You have a new prescription on Medicity",
                    "You have a new prescription. Sign in to view it.");
            case PRESCRIPTION_CORRECTED -> new Body("A prescription on Medicity was updated",
                    "One of your prescriptions was updated. Sign in to see the latest version before you take or collect any medicines.");
            case DOCTOR_AWAY -> new Body("Your Medicity appointment may not go ahead",
                    doctor + " will be away on the day of " + own + " appointment (" + when
                            + "). Sign in to move it to another time or cancel it.");
            case REMINDER -> new Body("Reminder: your Medicity appointment is tomorrow",
                    forWhom + " appointment with " + doctor + " is coming up on " + when
                            + ". If you cannot make it, please cancel or move it in the app so someone else can have the time.");
            default -> null;
        };
    }

    /** Plain-text version, with the footer. */
    static String text(Body body, String appUrl, String unsubscribeUrl) {
        StringBuilder sb = new StringBuilder(body.text()).append("\n\n");
        if (!appUrl.isEmpty()) {
            sb.append("Sign in: ").append(appUrl).append("\n\n");
        }
        sb.append("You get this email because of your Medicity account. ");
        sb.append("You can turn these emails off in your profile");
        sb.append(unsubscribeUrl.isEmpty() ? "." : ", or here: " + unsubscribeUrl);
        return sb.append("\n").toString();
    }

    static String html(Body body, String appUrl, String unsubscribeUrl) {
        StringBuilder sb = new StringBuilder("<div style=\"font-family:system-ui,sans-serif;max-width:480px\">")
                .append("<p>").append(HtmlUtils.htmlEscape(body.text())).append("</p>");
        if (!appUrl.isEmpty()) {
            sb.append("<p><a href=\"").append(HtmlUtils.htmlEscape(appUrl)).append("\">Sign in to Medicity</a></p>");
        }
        sb.append("<p style=\"color:#666;font-size:12px\">You get this email because of your Medicity account. ")
                .append("You can turn these emails off in your profile");
        if (!unsubscribeUrl.isEmpty()) {
            sb.append(", or <a href=\"").append(HtmlUtils.htmlEscape(unsubscribeUrl)).append("\">unsubscribe here</a>");
        }
        return sb.append(".</p></div>").toString();
    }

    private static String timeOf(JsonNode data) {
        String at = data.path("scheduledAt").asText("");
        if (at.isEmpty()) {
            return "the booked time";
        }
        return INDIA_TIME.format(Instant.parse(at)) + " IST";
    }
}
