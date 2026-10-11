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
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and verifies access tokens: short-lived, stateless JWTs.
 *
 * <p>Refresh tokens are not JWTs; they are opaque, stored and single-use (see
 * {@link RefreshTokenStore}). An access token cannot be revoked before it
 * expires on its own, which is why it lives 15 minutes; signing out revokes the
 * presented token at once through {@link AccessTokenDenylist} (by its {@code jti}).
 * A detected refresh token theft still cuts the thief's access token off only
 * within that window, because the stolen token's id is not known to the server.
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
                .id(UUID.randomUUID().toString())   // jti: lets sign-out revoke this token (AccessTokenDenylist)
                .subject(user.getId().toString())
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(now)
                .expiration(Date.from(clock.instant().plus(accessTtl)))
                .signWith(key)
                .compact();
    }

    /** A verified access token: whose it is, which token it is, and when it stops working anyway. */
    public record VerifiedToken(UUID userId, UUID tokenId, Instant expiresAt) {}

    /**
     * @return the token's details if this is a valid, unexpired access token.
     *         {@code tokenId} is null for a token issued before ids were added
     *         (at most one access lifetime after that deploy); those cannot be revoked.
     */
    public Optional<VerifiedToken> verifyAccessToken(String token) {
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
            String jti = claims.getId();
            return Optional.of(new VerifiedToken(UUID.fromString(claims.getSubject()),
                    jti == null ? null : UUID.fromString(jti), claims.getExpiration().toInstant()));

        } catch (JwtException | IllegalArgumentException e) {
            // Expired / tampered / malformed. Logged at DEBUG: an invalid token is
            // routine traffic, and logging the token itself would leak credentials.
            log.debug("Rejected token: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
