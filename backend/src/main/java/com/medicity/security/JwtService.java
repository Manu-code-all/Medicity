package com.medicity.security;

import com.medicity.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and verifies access tokens: short-lived, stateless JWTs.
 *
 * <p>Refresh tokens are not JWTs; they are opaque, stored and single-use (see
 * {@link RefreshTokenStore}). An access token cannot be revoked before it
 * expires, which is why it lives 15 minutes: signing out, or a detected refresh
 * token theft, cuts a session off within that window at most.
 */
@Service
@Slf4j
public class JwtService {

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_ACCESS = "access";

    private final SecretKey key;
    private final Duration accessTtl;
    private final Clock clock;

    public JwtService(@Value("${medicity.jwt.secret}") String secret,
                      @Value("${medicity.jwt.access-ttl}") Duration accessTtl,
                      Clock clock) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            // HS256 keys shorter than the hash output weaken the signature.
            // Fail at startup rather than shipping a forgeable token scheme.
            throw new IllegalStateException(
                    "medicity.jwt.secret must be at least 32 bytes; got " + bytes.length);
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.accessTtl = accessTtl;
        this.clock = clock;
    }

    public String issueAccessToken(User user) {
        Date now = Date.from(clock.instant());
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(now)
                .expiration(Date.from(clock.instant().plus(accessTtl)))
                .signWith(key)
                .compact();
    }

    /** @return the user id if this is a valid, unexpired access token. */
    public Optional<UUID> verifyAccessToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
                // Right signature, wrong purpose. Refresh tokens used to be JWTs
                // signed with this same key, with typ "refresh"; ones issued
                // before the switch stay correctly signed until they expire, and
                // this check keeps them from being accepted as bearer credentials.
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(claims.getSubject()));

        } catch (JwtException | IllegalArgumentException e) {
            // Expired / tampered / malformed. Logged at DEBUG: an invalid token is
            // routine traffic, and logging the token itself would leak credentials.
            log.debug("Rejected token: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
