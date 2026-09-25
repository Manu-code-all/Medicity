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
 * Issues and verifies JWTs.
 *
 * <p>Access tokens are short-lived and stateless; refresh tokens are long-lived
 * and carry a {@code typ} claim so a refresh token can never be replayed as an
 * access token. Without that distinction, a stolen refresh token would grant
 * immediate API access — a common oversight in JWT implementations.
 */
@Service
@Slf4j
public class JwtService {

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final Clock clock;

    public JwtService(@Value("${medicity.jwt.secret}") String secret,
                      @Value("${medicity.jwt.access-ttl}") Duration accessTtl,
                      @Value("${medicity.jwt.refresh-ttl}") Duration refreshTtl,
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
        this.refreshTtl = refreshTtl;
        this.clock = clock;
    }

    public String issueAccessToken(User user) {
        return issue(user, TYPE_ACCESS, accessTtl);
    }

    public String issueRefreshToken(User user) {
        return issue(user, TYPE_REFRESH, refreshTtl);
    }

    private String issue(User user, String type, Duration ttl) {
        Date now = Date.from(clock.instant());
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_TYPE, type)
                .issuedAt(now)
                .expiration(Date.from(clock.instant().plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** @return the user id if this is a valid, unexpired ACCESS token. */
    public Optional<UUID> verifyAccessToken(String token) {
        return verify(token, TYPE_ACCESS);
    }

    /** @return the user id if this is a valid, unexpired REFRESH token. */
    public Optional<UUID> verifyRefreshToken(String token) {
        return verify(token, TYPE_REFRESH);
    }

    private Optional<UUID> verify(String token, String expectedType) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            if (!expectedType.equals(claims.get(CLAIM_TYPE, String.class))) {
                // Right signature, wrong purpose — e.g. a refresh token presented
                // as a bearer credential.
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
