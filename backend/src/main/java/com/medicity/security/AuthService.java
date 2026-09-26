package com.medicity.security;

import com.medicity.audit.AuditLog;
import com.medicity.common.ConflictException;
import com.medicity.common.TooManyRequestsException;
import com.medicity.common.ValidationException;
import com.medicity.patient.Patient;
import com.medicity.patient.PatientRepository;
import com.medicity.user.Role;
import com.medicity.user.User;
import com.medicity.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PatientRepository patientRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditLog auditLog;
    private final RefreshTokenStore refreshTokens;
    private final LoginThrottle loginThrottle;
    private final Duration refreshTtl;
    private final Clock clock;

    /**
     * Compared against when the email is unknown, so a failed login costs one
     * full hash either way and response time does not reveal whether an
     * account exists.
     *
     * <p>Generated at startup by the application's own encoder rather than
     * written as a literal. It previously was a hand-typed literal, 59
     * characters where BCrypt needs 53; Spring rejects a malformed hash without
     * hashing, so unknown emails answered about 220 ms faster in production —
     * a user-enumeration leak hidden behind a comment claiming the opposite.
     * Encoding a real value guarantees the right format and the same cost
     * factor as every stored password, including after the cost is changed.
     */
    private final String timingEqualiserHash;

    public AuthService(UserRepository userRepository,
                       PatientRepository patientRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       AuditLog auditLog,
                       RefreshTokenStore refreshTokens,
                       LoginThrottle loginThrottle,
                       @Value("${medicity.jwt.refresh-ttl}") Duration refreshTtl,
                       Clock clock) {
        this.userRepository = userRepository;
        this.patientRepository = patientRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditLog = auditLog;
        this.refreshTokens = refreshTokens;
        this.loginThrottle = loginThrottle;
        this.refreshTtl = refreshTtl;
        this.clock = clock;
        this.timingEqualiserHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Self-service registration, which creates a PATIENT only.
     *
     * <p>Doctor and admin accounts are provisioned by an administrator. If the
     * requested role came from the request body, anyone could mint themselves an
     * admin account — a privilege-escalation hole that appears in a surprising
     * number of tutorial codebases.
     */
    @Transactional
    public TokenPair register(String email, String rawPassword, String fullName,
                              String phone, LocalDate dateOfBirth, Patient.Gender gender) {

        User user = User.builder()
                .passwordHash(passwordEncoder.encode(rawPassword))
                .fullName(fullName.trim())
                .phone(phone)
                .role(Role.PATIENT)
                .enabled(true)
                .build();
        user.setEmail(email);   // setter normalises to lowercase

        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Checking existsByEmail() first would leave a race between the check
            // and the insert. Let the unique index decide, then translate.
            throw new ConflictException("EMAIL_TAKEN", "An account with that email already exists");
        }

        patientRepository.save(Patient.builder()
                .user(user)
                .dateOfBirth(dateOfBirth)
                .gender(gender == null ? Patient.Gender.UNDISCLOSED : gender)
                .build());

        log.info("Registered patient account {}", user.getId());
        return startSession(user);
    }

    @Transactional
    public TokenPair login(String email, String rawPassword) {
        String normalised = email.trim().toLowerCase();

        // Before the account lookup, for every email alike: see LoginThrottle.
        try {
            loginThrottle.check(normalised);
        } catch (TooManyRequestsException e) {
            auditLog.recordIndependently("LOGIN_THROTTLED", "USER", null,
                    AuditLog.Outcome.DENIED, Map.of("email", normalised));
            throw e;
        }

        User user = userRepository.findByEmail(normalised)
                .orElse(null);

        // Hash even when the user does not exist; see timingEqualiserHash.
        String storedHash = user != null ? user.getPasswordHash() : timingEqualiserHash;

        boolean matches = passwordEncoder.matches(rawPassword, storedHash);

        if (user == null || !matches) {
            // Both branches write this row, so the audit adds equal time to each
            // and does not reintroduce the timing difference fixed above. The
            // attempted email is kept: it is what reveals credential stuffing.
            auditLog.recordIndependently("LOGIN_FAILED", "USER", user == null ? null : user.getId(),
                    AuditLog.Outcome.DENIED, Map.of("email", normalised));
            throw new BadCredentialsException("Invalid email or password");
        }
        if (!user.isEnabled()) {
            auditLog.recordIndependentlyAs(user.getId(), user.getRole().name(), "LOGIN_REFUSED_DISABLED",
                    "USER", user.getId(), AuditLog.Outcome.DENIED, null);
            throw new ValidationException("ACCOUNT_DISABLED", "This account has been disabled");
        }
        auditLog.recordIndependentlyAs(user.getId(), user.getRole().name(), "LOGIN_SUCCEEDED",
                "USER", user.getId(), AuditLog.Outcome.SUCCESS, null);
        return startSession(user);
    }

    /**
     * Exchanges a refresh token for a new pair, consuming it.
     *
     * <p>A token that was already used is evidence of theft: the legitimate
     * client and an attacker both hold it, and whichever used it second is
     * here now. The server cannot tell which one this is, so it ends the whole
     * session (the token family) and both must sign in again. The attacker's
     * access token still works until it expires, at most 15 minutes.
     *
     * <p>{@code noRollbackFor}: every refusal below is a 401, and the
     * revocation written just before it must survive that exception rather
     * than be rolled back with it.
     */
    @Transactional(noRollbackFor = BadCredentialsException.class)
    public TokenPair refresh(String refreshToken) {
        Instant now = clock.instant();
        Optional<RefreshTokenStore.TokenOwner> claimed = refreshTokens.claim(refreshToken, now);

        if (claimed.isEmpty()) {
            refreshTokens.find(refreshToken, now)
                    .filter(RefreshTokenStore.TokenState::used)
                    .ifPresent(reused -> {
                        int revoked = refreshTokens.revokeFamily(reused.familyId(), now);
                        log.warn("Refresh token reuse detected for user {}; revoked {} token(s) in family {}",
                                reused.userId(), revoked, reused.familyId());
                        auditLog.recordIndependentlyAs(reused.userId(), null, "REFRESH_TOKEN_REUSED",
                                "USER", reused.userId(), AuditLog.Outcome.DENIED,
                                Map.of("familyId", reused.familyId(), "tokensRevoked", revoked));
                    });
            // Unknown, expired, revoked or reused: one answer for all, so the
            // response does not tell a token thief which case they hit.
            throw invalidRefreshToken();
        }

        RefreshTokenStore.TokenOwner owner = claimed.get();
        User user = userRepository.findById(owner.userId()).orElseThrow(AuthService::invalidRefreshToken);
        if (!user.isEnabled()) {
            refreshTokens.revokeFamily(owner.familyId(), now);
            throw invalidRefreshToken();
        }
        return issue(user, owner.familyId(), now);
    }

    /**
     * Ends the session the refresh token belongs to. Succeeds whatever the
     * token's state, so signing out never fails and reveals nothing.
     */
    @Transactional
    public void logout(String refreshToken) {
        Instant now = clock.instant();
        refreshTokens.find(refreshToken, now).ifPresent(token -> {
            refreshTokens.revokeFamily(token.familyId(), now);
            auditLog.recordIndependentlyAs(token.userId(), null, "LOGOUT", "USER", token.userId(),
                    AuditLog.Outcome.SUCCESS, null);
        });
    }

    /** A new sign-in: a new token family. */
    private TokenPair startSession(User user) {
        return issue(user, UUID.randomUUID(), clock.instant());
    }

    private TokenPair issue(User user, UUID familyId, Instant now) {
        return new TokenPair(
                jwtService.issueAccessToken(user),
                refreshTokens.issue(user.getId(), familyId, now, now.plus(refreshTtl)),
                user.getId().toString(),
                user.getRole().name(),
                user.getFullName());
    }

    private static BadCredentialsException invalidRefreshToken() {
        return new BadCredentialsException("Invalid or expired refresh token");
    }

    public record TokenPair(String accessToken,
                            String refreshToken,
                            String userId,
                            String role,
                            String fullName) {}
}
