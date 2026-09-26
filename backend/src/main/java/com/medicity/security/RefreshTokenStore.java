package com.medicity.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for refresh tokens (V9). Only the SHA-256 of a token is stored,
 * so a copy of the table does not yield usable tokens.
 *
 * <p>Plain JDBC because the operations that matter are single conditional
 * statements — "mark used if still live" — whose atomicity is the whole point,
 * and which JPA would split into a read and a write with a race between them.
 */
@Component
public class RefreshTokenStore {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_SAFE = Base64.getUrlEncoder().withoutPadding();

    private final JdbcTemplate jdbc;

    public RefreshTokenStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What a claimed or looked-up token belongs to. */
    public record TokenOwner(UUID userId, UUID familyId) {}

    /** A stored token's state, for deciding why a claim failed. */
    public record TokenState(UUID userId, UUID familyId, boolean used, boolean revoked, boolean expired) {}

    /** Stores a new token in {@code familyId} and returns the raw value, which is never stored. */
    public String issue(UUID userId, UUID familyId, Instant now, Instant expiresAt) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = URL_SAFE.encodeToString(raw);
        jdbc.update("""
                INSERT INTO refresh_tokens (user_id, family_id, token_hash, issued_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, userId, familyId, hash(token), Timestamp.from(now), Timestamp.from(expiresAt));
        return token;
    }

    /**
     * Marks the token used if it is still live, in one statement.
     *
     * <p>Two requests presenting the same token cannot both succeed: the second
     * {@code UPDATE} waits on the first's row lock, then re-checks
     * {@code used_at IS NULL}, finds it set, and matches nothing.
     */
    public Optional<TokenOwner> claim(String token, Instant now) {
        return jdbc.query("""
                UPDATE refresh_tokens SET used_at = ?
                WHERE token_hash = ? AND used_at IS NULL AND revoked_at IS NULL AND expires_at > ?
                RETURNING user_id, family_id
                """,
                (rs, i) -> new TokenOwner(rs.getObject("user_id", UUID.class), rs.getObject("family_id", UUID.class)),
                Timestamp.from(now), hash(token), Timestamp.from(now)).stream().findFirst();
    }

    public Optional<TokenState> find(String token, Instant now) {
        return jdbc.query("""
                SELECT user_id, family_id, used_at IS NOT NULL AS used, revoked_at IS NOT NULL AS revoked,
                       expires_at <= ? AS expired
                FROM refresh_tokens WHERE token_hash = ?
                """,
                (rs, i) -> new TokenState(rs.getObject("user_id", UUID.class), rs.getObject("family_id", UUID.class),
                        rs.getBoolean("used"), rs.getBoolean("revoked"), rs.getBoolean("expired")),
                Timestamp.from(now), hash(token)).stream().findFirst();
    }

    /**
     * Revokes every token in the family, in the caller's transaction.
     *
     * <p>Not {@code REQUIRES_NEW}, although it is usually followed by a 401: the
     * caller may already hold a row lock in this family from {@link #claim}, and
     * a second transaction updating that row would wait for the first, which is
     * waiting for it — a deadlock the database cannot see, because one side is
     * idle rather than blocked. The caller instead keeps its transaction from
     * rolling back on the 401 (see {@code AuthService.refresh}).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int revokeFamily(UUID familyId, Instant now) {
        return jdbc.update("UPDATE refresh_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL",
                Timestamp.from(now), familyId);
    }

    static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
