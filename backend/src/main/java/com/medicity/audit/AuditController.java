package com.medicity.audit;

import com.fasterxml.jackson.annotation.JsonRawValue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only view of the audit trail, for administrators. */
@RestController
@RequestMapping("/api/v1/admin/audit")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Audit")
public class AuditController {

    private final JdbcTemplate jdbc;

    /**
     * Newest first. Every filter is optional; {@code limit} is capped so one
     * request cannot pull the whole table.
     */
    @GetMapping
    @Operation(summary = "Search the audit trail")
    public List<AuditEntry> search(@RequestParam(required = false) String entityType,
                                   @RequestParam(required = false) String entityId,
                                   @RequestParam(required = false) UUID actorId,
                                   @RequestParam(required = false) String outcome,
                                   @RequestParam(defaultValue = "50") int limit) {
        return jdbc.query("""
                        SELECT id, occurred_at, actor_id, actor_role, action, entity_type, entity_id,
                               outcome, host(ip_address) AS ip, detail::text AS detail
                        FROM audit_log
                        WHERE (CAST(? AS text) IS NULL OR entity_type = ?)
                          AND (CAST(? AS text) IS NULL OR entity_id = ?)
                          AND (CAST(? AS uuid) IS NULL OR actor_id = ?)
                          AND (CAST(? AS text) IS NULL OR outcome = ?)
                        ORDER BY occurred_at DESC, id DESC
                        LIMIT ?
                        """,
                (rs, i) -> new AuditEntry(
                        rs.getLong("id"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getObject("actor_id", UUID.class),
                        rs.getString("actor_role"),
                        rs.getString("action"),
                        rs.getString("entity_type"),
                        rs.getString("entity_id"),
                        rs.getString("outcome"),
                        rs.getString("ip"),
                        rs.getString("detail")),
                entityType, entityType,
                entityId, entityId,
                actorId, actorId,
                outcome, outcome,
                Math.min(Math.max(limit, 1), 200));
    }

    public record AuditEntry(
            long id,
            Instant occurredAt,
            UUID actorId,
            String actorRole,
            String action,
            String entityType,
            String entityId,
            String outcome,
            String ipAddress,
            @JsonRawValue String detail
    ) {}
}
