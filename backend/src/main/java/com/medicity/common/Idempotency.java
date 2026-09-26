package com.medicity.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Makes a write safe to retry: the first request with a given
 * {@code Idempotency-Key} runs; any later one with the same key gets the first
 * one's response back instead of running again.
 *
 * <p>The key row is inserted <em>before</em> the action runs, in the same
 * transaction. A concurrent duplicate's insert then waits on that uncommitted
 * row: if the first request commits, the duplicate finds its stored response;
 * if it rolls back, the row disappears and the duplicate runs the action
 * itself. No lock is taken by hand and no request ever sees a half-finished
 * entry.
 *
 * <p>Only successes are stored. A failed request rolls back its key with
 * everything else, so retrying it runs it again — which is what a client
 * retrying after a 409 or a 500 wants.
 */
@Component
public class Idempotency {

    /** How long a key is remembered. Retries come within seconds; a day is generous. */
    static final Duration RETENTION = Duration.ofHours(24);

    /** Printable ASCII without spaces, as the IETF draft for the header recommends; a UUID fits easily. */
    private static final Pattern VALID_KEY = Pattern.compile("[\\x21-\\x7E]{1,255}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final DomainMetrics metrics;

    public Idempotency(JdbcTemplate jdbc, ObjectMapper json, Clock clock, DomainMetrics metrics) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.metrics = metrics;
    }

    public record Result<T>(T body, boolean replayed) {}

    /**
     * Runs {@code action} once per {@code (userId, key)}.
     *
     * @param request the request as received; a key reused with a different
     *                request is refused rather than answered with a response
     *                that belongs to something else
     * @throws ValidationException {@code IDEMPOTENCY_KEY_REUSED} in that case
     */
    @Transactional
    public <T> Result<T> run(UUID userId, String key, String operation, Object request,
                             int successStatus, Class<T> responseType, Supplier<T> action) {
        if (!VALID_KEY.matcher(key).matches()) {
            throw new BadRequestException("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 1 to 255 printable characters without spaces");
        }
        Instant now = clock.instant();
        byte[] fingerprint = sha256(operation + "\n" + write(request));

        // An expired key is forgotten, so the same key can start fresh.
        jdbc.update("DELETE FROM idempotency_keys WHERE user_id = ? AND idempotency_key = ? AND created_at < ?",
                userId, key, Timestamp.from(now.minus(RETENTION)));

        int claimed = jdbc.update("""
                INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash, created_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id, idempotency_key) DO NOTHING
                """, userId, key, fingerprint, Timestamp.from(now));

        if (claimed == 1) {
            T body = action.get();
            jdbc.update("""
                    UPDATE idempotency_keys SET response_status = ?, response_body = CAST(? AS jsonb)
                    WHERE user_id = ? AND idempotency_key = ?
                    """, successStatus, write(body), userId, key);
            return new Result<>(body, false);
        }

        // The key was used before, and that request committed.
        return jdbc.query("""
                        SELECT request_hash, response_body::text AS body
                        FROM idempotency_keys WHERE user_id = ? AND idempotency_key = ?
                        """,
                (rs, i) -> {
                    if (!Arrays.equals(rs.getBytes("request_hash"), fingerprint)) {
                        throw new ValidationException("IDEMPOTENCY_KEY_REUSED",
                                "This Idempotency-Key was already used for a different request");
                    }
                    metrics.idempotentReplay();
                    return new Result<>(read(rs.getString("body"), responseType), true);
                },
                userId, key).stream().findFirst()
                // The other request committed and then its key expired and was
                // deleted in between: vanishingly rare, and safe to report as a conflict.
                .orElseThrow(() -> new ConflictException("IDEMPOTENCY_KEY_BUSY",
                        "A request with this Idempotency-Key is still being processed. Try again."));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise " + value.getClass().getSimpleName(), e);
        }
    }

    private <T> T read(String body, Class<T> type) {
        try {
            return json.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored idempotent response is not a " + type.getSimpleName(), e);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
