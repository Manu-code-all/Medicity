package com.medicity.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Access tokens revoked before they expired (sign-out), by token id.
 *
 * <p>Checked on every authenticated request: one primary-key lookup, next to
 * the one that re-loads the user. Rows are deleted by {@code HousekeepingJob}
 * once the token's own expiry has passed, since it would be refused anyway.
 */
@Component
public class AccessTokenDenylist {

    private final JdbcTemplate jdbc;

    public AccessTokenDenylist(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent: revoking the same token twice is not an error. */
    public void revoke(UUID tokenId, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO revoked_access_tokens (token_id, expires_at) VALUES (?, ?)
                ON CONFLICT (token_id) DO NOTHING
                """, tokenId, Timestamp.from(expiresAt));
    }

    public boolean isRevoked(UUID tokenId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM revoked_access_tokens WHERE token_id = ?)", Boolean.class, tokenId));
    }
}
