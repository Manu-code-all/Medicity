package com.medicity.security;

import com.medicity.audit.AuditLog;
import com.medicity.common.DomainException;
import com.medicity.common.EmailAddresses;
import com.medicity.common.PhoneNumbers;
import com.medicity.common.ValidationException;
import com.medicity.user.User;
import com.medicity.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Signing in with a mobile number and a six-digit code.
 *
 * <p>Decisions worth knowing:
 * <ul>
 *   <li><b>The answer to "send a code" never says whether the number has an
 *       account.</b> With SMS on, every valid number gets the same reply. The
 *       only exception is the public demo accounts, whose codes are shown on
 *       screen while no SMS provider is configured.</li>
 *   <li><b>A code is good for five minutes, five guesses and one sign-in,</b>
 *       and only the newest code for an account counts. At most three codes
 *       are sent to an account in fifteen minutes, which bounds both guessing
 *       (15 guesses at a one-in-a-million space) and SMS bombing. A request
 *       over the limit is answered like any other and sends nothing, so the
 *       limit cannot be used to discover accounts either.</li>
 *   <li><b>The code is stored only as an HMAC</b> keyed by the server secret
 *       and bound to its row, and checked in constant time.</li>
 *   <li><b>A code can also go to the account's email address.</b> It is the
 *       same code, with the same lifetime, guess limit and send limit, and the
 *       limits are shared: three codes in fifteen minutes, whichever way they
 *       were sent. See {@link #sendToEmail}.</li>
 * </ul>
 */
@Service
@Slf4j
public class OtpService {

    static final Duration LIFETIME = Duration.ofMinutes(5);
    static final int MAX_ATTEMPTS = 5;
    static final int MAX_SENDS = 3;
    static final Duration SEND_WINDOW = Duration.ofMinutes(15);

    private static final String DEMO_DOMAIN = "@medicity.demo";

    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final AuditLog auditLog;
    private final OtpSender sender;
    private final EmailOtpSender emailSender;
    private final MailDispatcher mail;
    private final Clock clock;
    private final boolean showDemoCodes;
    private final byte[] secret;
    private final SecureRandom random = new SecureRandom();

    public OtpService(UserRepository users, JdbcTemplate jdbc, AuthService auth, AuditLog auditLog,
                      OtpSender sender, EmailOtpSender emailSender, MailDispatcher mail, Clock clock,
                      @Value("${medicity.auth.otp.show-demo-codes:false}") boolean showDemoCodes,
                      @Value("${medicity.jwt.secret}") String secret) {
        this.users = users;
        this.jdbc = jdbc;
        this.auth = auth;
        this.auditLog = auditLog;
        this.sender = sender;
        this.emailSender = emailSender;
        this.mail = mail;
        this.clock = clock;
        this.showDemoCodes = showDemoCodes;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public enum Delivery { SMS, EMAIL, DEMO, UNAVAILABLE }

    /** What the caller is told. {@code demoCode} is set only for a demo account without SMS. */
    public record CodeSent(Delivery delivery, String sentTo, String demoCode, int expiresInSeconds) {}

    @Transactional
    public CodeSent send(String typedPhone) {
        String phone = PhoneNumbers.normalise(typedPhone);
        if (phone == null) {
            throw new ValidationException("INVALID_PHONE", "Enter a 10 digit Indian mobile number");
        }
        Optional<User> account = users.findByLoginPhone(phone).filter(User::isEnabled);
        boolean demo = showDemoCodes && account.map(u -> u.getEmail().endsWith(DEMO_DOMAIN)).orElse(false);

        if (!demo && !sender.available()) {
            // Told plainly, and the same for every number, so it reveals nothing.
            return new CodeSent(Delivery.UNAVAILABLE, null, null, 0);
        }
        CodeSent same = new CodeSent(demo ? Delivery.DEMO : Delivery.SMS, PhoneNumbers.masked(phone), null,
                (int) LIFETIME.toSeconds());
        if (account.isEmpty()) {
            return same;
        }
        User user = account.get();

        // Demo codes are never texted, and many visitors share a demo account,
        // so only real numbers are limited.
        if (!demo && throttled(user)) {
            return same;
        }

        String code = issue(user);

        if (demo) {
            return new CodeSent(Delivery.DEMO, same.sentTo(), code, same.expiresInSeconds());
        }
        sender.send(phone, code);
        auditLog.recordIndependentlyAs(user.getId(), user.getRole().name(), "OTP_SENT", "USER", user.getId(),
                AuditLog.Outcome.SUCCESS, null);
        return same;
    }

    /**
     * Emails a sign-in code to the address an account was registered with.
     *
     * <p>As for a mobile number, the answer never says whether the address has
     * an account. It must not take longer for one that does, either: calling
     * the email provider takes a few hundred milliseconds, and a reply that
     * waited for it would tell anyone with a stopwatch which addresses are
     * registered. So the message is queued to go out <em>after</em> the code
     * is committed and the reply does not wait for it. The cost is that a
     * failed send is not reported to the person asking; it is logged and
     * audited, and they ask again.
     */
    @Transactional
    public CodeSent sendToEmail(String typedEmail) {
        String email = EmailAddresses.normalise(typedEmail);
        if (email == null || !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new ValidationException("INVALID_EMAIL", "Enter a valid email address");
        }
        Optional<User> account = users.findByEmail(email).filter(User::isEnabled);
        boolean demo = showDemoCodes && account.isPresent() && email.endsWith(DEMO_DOMAIN);

        if (!demo && !emailSender.available()) {
            return new CodeSent(Delivery.UNAVAILABLE, null, null, 0);
        }
        CodeSent same = new CodeSent(demo ? Delivery.DEMO : Delivery.EMAIL, EmailAddresses.masked(email), null,
                (int) LIFETIME.toSeconds());
        if (account.isEmpty()) {
            return same;
        }
        User user = account.get();
        if (!demo && throttled(user)) {
            return same;
        }

        String code = issue(user);

        if (demo) {
            return new CodeSent(Delivery.DEMO, same.sentTo(), code, same.expiresInSeconds());
        }
        afterCommit(() -> mail.dispatch(() -> deliver(user, email, code)));
        return same;
    }

    /** Runs on the mail thread, where there is no request to fail: report to the log and the audit trail. */
    private void deliver(User user, String email, String code) {
        try {
            emailSender.send(email, code);
            auditLog.recordIndependentlyAs(user.getId(), user.getRole().name(), "OTP_SENT", "USER", user.getId(),
                    AuditLog.Outcome.SUCCESS, Map.of("channel", "email"));
        } catch (RuntimeException e) {
            log.warn("Sign-in code email to user {} not sent: {}", user.getId(), e.getMessage());
            auditLog.recordIndependentlyAs(user.getId(), user.getRole().name(), "OTP_SEND_FAILED", "USER",
                    user.getId(), AuditLog.Outcome.ERROR, Map.of("channel", "email"));
        }
    }

    /**
     * Only once the code row is committed: the email can arrive within a second
     * and the person types it straight away, and a code emailed for a
     * transaction that then rolled back would be a code that never works.
     */
    private static void afterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    /** At most {@link #MAX_SENDS} codes per account per {@link #SEND_WINDOW}, whichever way they were sent. */
    private boolean throttled(User user) {
        if (sentRecently(user.getId(), clock.instant()) < MAX_SENDS) {
            return false;
        }
        auditLog.recordIndependentlyAs(user.getId(), user.getRole().name(), "OTP_THROTTLED", "USER",
                user.getId(), AuditLog.Outcome.DENIED, null);
        return true;
    }

    /** Records a new live code for the account and returns it; the only copy outside the hash. */
    private String issue(User user) {
        Instant now = clock.instant();
        String code = "%06d".formatted(random.nextInt(1_000_000));
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO otp_challenges (id, user_id, code_hash, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, id, user.getId(), hash(id, code), Timestamp.from(now), Timestamp.from(now.plus(LIFETIME)));
        return code;
    }

    /**
     * Signs in with the newest live code for the number.
     *
     * <p>{@code noRollbackFor}: a wrong guess must still count against the
     * code, so the attempt written before the refusal has to survive it.
     */
    @Transactional(noRollbackFor = OtpService.WrongCode.class)
    public AuthService.TokenPair verify(String typedPhone, String code) {
        String phone = PhoneNumbers.normalise(typedPhone);
        if (phone == null || code == null || !code.matches("[0-9]{6}")) {
            throw wrongCode();
        }
        User user = users.findByLoginPhone(phone).filter(User::isEnabled).orElseThrow(OtpService::wrongCode);
        return verifyFor(user, code);
    }

    /** As {@link #verify}, for a code that was emailed. The same code, limits and single use. */
    @Transactional(noRollbackFor = OtpService.WrongCode.class)
    public AuthService.TokenPair verifyEmail(String typedEmail, String code) {
        String email = EmailAddresses.normalise(typedEmail);
        if (email == null || code == null || !code.matches("[0-9]{6}")) {
            throw wrongCode();
        }
        User user = users.findByEmail(email).filter(User::isEnabled).orElseThrow(OtpService::wrongCode);
        return verifyFor(user, code);
    }

    private AuthService.TokenPair verifyFor(User user, String code) {
        Instant now = clock.instant();

        // Locked, so two guesses at once are counted one after the other.
        List<Map<String, Object>> live = jdbc.queryForList("""
                SELECT id, code_hash FROM otp_challenges
                WHERE user_id = ? AND consumed_at IS NULL AND expires_at > ? AND attempts < ?
                ORDER BY seq DESC LIMIT 1
                FOR UPDATE
                """, user.getId(), Timestamp.from(now), MAX_ATTEMPTS);
        if (live.isEmpty() || !isNewest(user.getId(), (UUID) live.get(0).get("id"))) {
            throw wrongCode();
        }
        UUID id = (UUID) live.get(0).get("id");
        jdbc.update("UPDATE otp_challenges SET attempts = attempts + 1 WHERE id = ?", id);

        boolean matches = MessageDigest.isEqual(
                hash(id, code).getBytes(StandardCharsets.US_ASCII),
                ((String) live.get(0).get("code_hash")).getBytes(StandardCharsets.US_ASCII));
        if (!matches) {
            auditLog.recordIndependentlyAs(user.getId(), user.getRole().name(), "LOGIN_FAILED", "USER",
                    user.getId(), AuditLog.Outcome.DENIED, Map.of("method", "otp"));
            throw wrongCode();
        }
        jdbc.update("UPDATE otp_challenges SET consumed_at = ? WHERE id = ?", Timestamp.from(now), id);
        auditLog.recordIndependentlyAs(user.getId(), user.getRole().name(), "LOGIN_SUCCEEDED", "USER",
                user.getId(), AuditLog.Outcome.SUCCESS, Map.of("method", "otp"));
        return auth.startSession(user);
    }

    /** A newer code replaces an older one even if the older is still live. */
    private boolean isNewest(UUID userId, UUID challengeId) {
        UUID newest = jdbc.queryForObject(
                "SELECT id FROM otp_challenges WHERE user_id = ? ORDER BY seq DESC LIMIT 1",
                UUID.class, userId);
        return challengeId.equals(newest);
    }

    private int sentRecently(UUID userId, Instant now) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM otp_challenges WHERE user_id = ? AND created_at > ?",
                Integer.class, userId, Timestamp.from(now.minus(SEND_WINDOW)));
        return n == null ? 0 : n;
    }

    private String hash(UUID id, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((id + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private static WrongCode wrongCode() {
        return new WrongCode();
    }

    /** One answer for a wrong, expired, used or unknown code. */
    public static class WrongCode extends DomainException {
        WrongCode() {
            super(HttpStatus.UNAUTHORIZED, "WRONG_CODE", "That code is wrong or has expired. Ask for a new one.");
        }
    }
}
