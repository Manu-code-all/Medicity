package com.medicity.notification;

import com.medicity.common.NotFoundException;
import com.medicity.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The signed-in user's own notifications. As with the portal, no route takes
 * a user id: every query is scoped by the token's user, so there is nothing
 * to change to reach someone else's. Marking another user's notification as
 * read matches no row and answers 404.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications")
public class NotificationController {

    private static final int LIMIT = 50;

    private final JdbcTemplate jdbc;

    public NotificationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record NotificationResponse(UUID id, String kind, String title, String body, String link,
                                       Instant occursAt, Instant createdAt, boolean read) {}

    public record NotificationList(int unread, List<NotificationResponse> items) {}

    @GetMapping
    @Operation(summary = "My most recent notifications, newest first, with the unread count")
    public NotificationList mine(@AuthenticationPrincipal AppUserPrincipal principal) {
        List<NotificationResponse> items = jdbc.query("""
                SELECT id, kind, title, body, link, occurs_at, created_at, read_at IS NOT NULL AS read
                FROM notifications WHERE user_id = ?
                ORDER BY created_at DESC LIMIT ?
                """, (rs, n) -> new NotificationResponse(
                        rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("title"),
                        rs.getString("body"), rs.getString("link"),
                        rs.getTimestamp("occurs_at") == null ? null : rs.getTimestamp("occurs_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant(), rs.getBoolean("read")),
                principal.getId(), LIMIT);
        Integer unread = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id = ? AND read_at IS NULL",
                Integer.class, principal.getId());
        return new NotificationList(unread == null ? 0 : unread, items);
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Mark one of my notifications as read")
    public void markRead(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable UUID id) {
        int updated = jdbc.update(
                "UPDATE notifications SET read_at = coalesce(read_at, now()) WHERE id = ? AND user_id = ?",
                id, principal.getId());
        if (updated == 0) {
            throw new NotFoundException("Notification", id);
        }
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Mark all my notifications as read")
    public void markAllRead(@AuthenticationPrincipal AppUserPrincipal principal) {
        jdbc.update("UPDATE notifications SET read_at = now() WHERE user_id = ? AND read_at IS NULL",
                principal.getId());
    }
}
