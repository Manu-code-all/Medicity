package com.medicity.notification.email;

import com.medicity.audit.AuditLog;
import com.medicity.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;

import java.util.Map;
import java.util.UUID;

/**
 * The email opt-out: a setting for the signed-in user, and the page behind the
 * unsubscribe link in every email.
 *
 * <p>The link is a GET that only shows a button, and the POST behind the button
 * does the work. Mail scanners and link previewers fetch every URL in a
 * message; if opening the link unsubscribed, they would unsubscribe people who
 * never asked. The POST is also the form RFC 8058 "one-click" unsubscribe
 * uses, so mail clients that offer their own unsubscribe button work too.
 */
@RestController
@Tag(name = "Notifications")
public class EmailPreferenceController {

    private final EmailPreferences preferences;
    private final EmailSender sender;
    private final AuditLog auditLog;

    public EmailPreferenceController(EmailPreferences preferences, EmailSender sender, AuditLog auditLog) {
        this.preferences = preferences;
        this.sender = sender;
        this.auditLog = auditLog;
    }

    /** @param available whether the server can send email at all; the toggle is pointless when it cannot */
    public record EmailPreference(boolean enabled, boolean available) {}

    public record EmailPreferenceUpdate(@NotNull Boolean enabled) {}

    @GetMapping("/api/v1/notifications/email-preference")
    @Operation(summary = "Whether I get notification emails")
    public EmailPreference mine(@AuthenticationPrincipal AppUserPrincipal principal) {
        return new EmailPreference(preferences.enabled(principal.getId()), sender.available());
    }

    @PutMapping("/api/v1/notifications/email-preference")
    @Operation(summary = "Turn notification emails on or off")
    @Transactional
    public EmailPreference update(@AuthenticationPrincipal AppUserPrincipal principal,
                                  @Valid @RequestBody EmailPreferenceUpdate update) {
        preferences.set(principal.getId(), update.enabled());
        auditLog.recordChange("EMAIL_PREFERENCE_CHANGED", "USER", principal.getId(),
                Map.of("enabled", update.enabled()));
        return new EmailPreference(update.enabled(), sender.available());
    }

    @GetMapping(value = "/api/v1/notifications/email/unsubscribe", produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "The page behind an email's unsubscribe link (shows a button; changes nothing)")
    public ResponseEntity<String> unsubscribePage(@RequestParam("u") UUID userId, @RequestParam("t") String token) {
        if (!preferences.validToken(userId, token)) {
            return page(400, "This link is not valid", "Sign in and use your profile to turn emails off.");
        }
        return page(200, "Stop Medicity emails?", """
                <form method="post" action="/api/v1/notifications/email/unsubscribe?u=%s&amp;t=%s">
                <button type="submit">Yes, stop emailing me</button></form>
                <p>You will still see notifications in the app.</p>
                """.formatted(userId, HtmlUtils.htmlEscape(token)));
    }

    @PostMapping(value = "/api/v1/notifications/email/unsubscribe", produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "Turn emails off from the link in an email (also the one-click form of RFC 8058)")
    @Transactional
    public ResponseEntity<String> unsubscribe(@RequestParam("u") UUID userId, @RequestParam("t") String token) {
        if (!preferences.validToken(userId, token)) {
            return page(400, "This link is not valid", "Sign in and use your profile to turn emails off.");
        }
        preferences.set(userId, false);
        auditLog.recordIndependentlyAs(userId, null, "EMAIL_UNSUBSCRIBED", "USER", userId,
                AuditLog.Outcome.SUCCESS, Map.of("via", "email link"));
        return page(200, "You are unsubscribed",
                "We will not email you again. You can turn emails back on in your profile.");
    }

    private static ResponseEntity<String> page(int status, String heading, String body) {
        String html = """
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Medicity</title></head>
                <body style="font-family:system-ui,sans-serif;max-width:420px;margin:48px auto;padding:0 16px">
                <h1 style="font-size:20px">%s</h1><div>%s</div></body></html>
                """.formatted(heading, body);
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store").body(html);
    }
}
