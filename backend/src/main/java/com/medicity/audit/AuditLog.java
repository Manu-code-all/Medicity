package com.medicity.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicity.security.AppUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.UUID;

/**
 * Writes to {@code audit_log}: who did what to which record, and whether it
 * was allowed. The table is append-only, enforced by triggers (V5, V6).
 *
 * <p>Two entry points, because the right transaction depends on the event:
 * <ul>
 *   <li>{@link #recordChange} joins the caller's transaction ({@code MANDATORY}).
 *       A booking and its audit row commit or roll back together, so the log
 *       never claims a booking that did not happen, and never misses one that did.</li>
 *   <li>{@link #recordIndependently} commits in its own transaction
 *       ({@code REQUIRES_NEW}). Used for denials and failed logins, which are
 *       followed by an exception that rolls back the request's transaction; an
 *       audit row written there would be rolled back with it, erasing exactly the
 *       events an investigator most needs. Also used for reads, which have no
 *       transaction of their own to join, or only a read-only one.</li>
 * </ul>
 *
 * <p>Written with plain JDBC rather than a JPA entity: the table has no
 * updatable state, uses {@code inet} and {@code jsonb}, and is never read back
 * as an object graph.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditLog {

    public enum Outcome { SUCCESS, DENIED, ERROR }

    private static final String INSERT = """
            INSERT INTO audit_log (actor_id, actor_role, action, entity_type, entity_id, outcome, ip_address, detail)
            VALUES (?, ?, ?, ?, ?, ?, CAST(? AS inet), CAST(? AS jsonb))
            """;

    /** {@code audit_log.entity_id} is VARCHAR(255) (V12). */
    static final int ENTITY_ID_MAX = 255;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    /** A successful change, recorded atomically with it. Must be called inside a transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordChange(String action, String entityType, Object entityId, Map<String, ?> detail) {
        write(currentActorId(), currentActorRole(), action, entityType, entityId, Outcome.SUCCESS, detail);
    }

    /** An event that must be kept even if the surrounding request fails. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependently(String action, String entityType, Object entityId,
                                    Outcome outcome, Map<String, ?> detail) {
        writeBestEffort(currentActorId(), currentActorRole(), action, entityType, entityId, outcome, detail);
    }

    /**
     * As {@link #recordIndependently}, for events where the actor is known but
     * not yet authenticated on this request, such as a successful login.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependentlyAs(UUID actorId, String actorRole, String action, String entityType,
                                      Object entityId, Outcome outcome, Map<String, ?> detail) {
        writeBestEffort(actorId, actorRole, action, entityType, entityId, outcome, detail);
    }

    /**
     * Independent events accompany a response that is already decided (a 403,
     * a 401). If the audit write fails, that response must still go out: a
     * database hiccup must not turn a denial into a 500. The failure is logged
     * at ERROR so it is alerted on. Changes do not get this treatment; there,
     * a failed audit write rolls back the change itself.
     */
    private void writeBestEffort(UUID actorId, String actorRole, String action, String entityType,
                                 Object entityId, Outcome outcome, Map<String, ?> detail) {
        try {
            write(actorId, actorRole, action, entityType, entityId, outcome, detail);
        } catch (DataAccessException e) {
            log.error("AUDIT WRITE FAILED action={} entityType={} entityId={} outcome={}",
                    action, entityType, entityId, outcome, e);
        }
    }

    private void write(UUID actorId, String actorRole, String action, String entityType, Object entityId,
                       Outcome outcome, Map<String, ?> detail) {
        jdbc.update(INSERT,
                actorId,
                actorRole,
                action,
                entityType,
                entityId == null ? null : fit(entityId.toString()),
                outcome.name(),
                clientIp(),
                toJson(detail));
    }

    /**
     * An endpoint denial's id is "METHOD /path", and the path is whatever the
     * client sent. Cut to fit, rather than let an over-long URL make the insert
     * fail: a failed audit write loses the row, and a long URL would then be a
     * way to be denied without a trace.
     */
    private static String fit(String entityId) {
        return entityId.length() <= ENTITY_ID_MAX ? entityId : entityId.substring(0, ENTITY_ID_MAX - 1) + "…";
    }

    private String toJson(Map<String, ?> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        try {
            return json.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            // Losing the detail is better than losing the audit row.
            log.warn("Could not serialise audit detail for keys {}", detail.keySet(), e);
            return null;
        }
    }

    private static AppUserPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AppUserPrincipal p ? p : null;
    }

    private static UUID currentActorId() {
        AppUserPrincipal p = currentPrincipal();
        return p == null ? null : p.getId();
    }

    private static String currentActorRole() {
        AppUserPrincipal p = currentPrincipal();
        return p == null ? null : p.getRole().name();
    }

    /**
     * The caller's address. Behind the hosting proxy, Tomcat's RemoteIpValve
     * ({@code server.forward-headers-strategy: native}) resolves the forwarded
     * client address, trusting only proxies on private ranges so a client cannot
     * spoof its address with its own {@code X-Forwarded-For} header.
     * Absent outside an HTTP request (a scheduled job, a test calling a service).
     */
    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            return request.getRemoteAddr();
        }
        return null;
    }
}
