package com.medicity.notification.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

/**
 * Who has turned notification emails off, and the signed link that lets them
 * do it from the email itself without signing in.
 *
 * <p>The link carries the user id and an HMAC of it, keyed from the server's
 * secret and scoped ("email-unsubscribe"), so a token cannot be used for
 * anything else and nobody can unsubscribe someone else by guessing. It never
 * expires: an old email's unsubscribe link must keep working. The only thing
 * it can do is switch emails off for that one account.
 */
@Component
public class EmailPreferences {

    private final JdbcTemplate jdbc;
    private final byte[] key;

    public EmailPreferences(JdbcTemplate jdbc, @Value("${medicity.jwt.secret}") String secret) {
        this.jdbc = jdbc;
        this.key = ("email-unsubscribe:" + secret).getBytes(StandardCharsets.UTF_8);
    }

    /** On unless the person has chosen otherwise. */
    public boolean enabled(UUID userId) {
        return Boolean.TRUE.equals(jdbc.query(
                "SELECT enabled FROM user_email_preferences WHERE user_id = ?",
                rs -> rs.next() ? rs.getBoolean(1) : true, userId));
    }

    public void set(UUID userId, boolean enabled) {
        jdbc.update("""
                INSERT INTO user_email_preferences (user_id, enabled) VALUES (?, ?)
                ON CONFLICT (user_id) DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = now()
                """, userId, enabled);
    }

    public String token(UUID userId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(userId.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    public boolean validToken(UUID userId, String token) {
        return token != null && MessageDigest.isEqual(
                token(userId).getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }
}
