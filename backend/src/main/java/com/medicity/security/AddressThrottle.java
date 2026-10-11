package com.medicity.security;

import com.medicity.audit.AuditLog;
import com.medicity.common.TooManyRequestsException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Per-client-address limits, built the way {@link LoginThrottle} is: the count
 * comes from rows the audit log already holds, so it is shared by every
 * instance, survives restarts and cannot be reset by an attacker.
 *
 * <ul>
 *   <li><b>Failed logins per address:</b> {@code LOGIN_FAILED} rows, whatever
 *       email they named. Catches one machine trying many accounts, which the
 *       per-email limit cannot. The default is far above the per-email one so
 *       an office or a mobile carrier sharing one address is not locked out by
 *       a few typos.</li>
 *   <li><b>Sign-ups per address:</b> every patient, doctor or chemist sign-up
 *       request records a {@code SIGNUP_ATTEMPT} row (whether or not it then
 *       succeeds), and requests beyond the limit get 429 and a
 *       {@code SIGNUP_THROTTLED} row, which is not counted, so hammering does
 *       not extend the limit.</li>
 * </ul>
 *
 * <p><b>Only as good as the address.</b> Behind a proxy the address is whatever
 * Tomcat's RemoteIpValve resolves. Unless {@code server.tomcat.remoteip.internal-proxies}
 * names the real proxies, every client shares the proxy's address and these
 * limits would throttle everyone together; hence the generous defaults, and
 * hence they do nothing when there is no address (a call outside an HTTP request).
 */
@Component
public class AddressThrottle {

    private final JdbcTemplate jdbc;
    private final AuditLog auditLog;
    private final int maxLoginFailures;
    private final Duration loginWindow;
    private final int maxSignUps;
    private final Duration signUpWindow;

    public AddressThrottle(JdbcTemplate jdbc,
                           AuditLog auditLog,
                           @Value("${medicity.auth.address-login-throttle.max-failures:30}") int maxLoginFailures,
                           @Value("${medicity.auth.address-login-throttle.window:PT15M}") Duration loginWindow,
                           @Value("${medicity.auth.signup-throttle.max-attempts:10}") int maxSignUps,
                           @Value("${medicity.auth.signup-throttle.window:PT1H}") Duration signUpWindow) {
        this.jdbc = jdbc;
        this.auditLog = auditLog;
        this.maxLoginFailures = maxLoginFailures;
        this.loginWindow = loginWindow;
        this.maxSignUps = maxSignUps;
        this.signUpWindow = signUpWindow;
    }

    /** @throws TooManyRequestsException if this address has failed too many logins lately */
    public void checkLogin() {
        String ip = ClientIp.current();
        if (ip == null) return;
        long wait = secondsUntilAllowed("LOGIN_FAILED", ip, maxLoginFailures, loginWindow);
        if (wait > 0) {
            // AuthService audits the refusal (LOGIN_THROTTLED) for both limits.
            throw new TooManyRequestsException("TOO_MANY_LOGIN_ATTEMPTS",
                    "Too many failed sign-in attempts from this network. Try again in %d minute%s."
                            .formatted(minutes(wait), minutes(wait) == 1 ? "" : "s"),
                    wait);
        }
    }

    /**
     * Counts this sign-up request against the caller's address.
     *
     * @param kind {@code patient}, {@code doctor} or {@code chemist}; kept in the audit row
     * @throws TooManyRequestsException if the address has made too many sign-up requests lately
     */
    public void checkSignUp(String kind) {
        String ip = ClientIp.current();
        if (ip == null) return;
        long wait = secondsUntilAllowed("SIGNUP_ATTEMPT", ip, maxSignUps, signUpWindow);
        if (wait > 0) {
            auditLog.recordIndependently("SIGNUP_THROTTLED", "USER", null, AuditLog.Outcome.DENIED,
                    Map.of("kind", kind));
            throw new TooManyRequestsException("TOO_MANY_SIGNUPS",
                    "Too many accounts have been created from this network. Try again in %d minute%s."
                            .formatted(minutes(wait), minutes(wait) == 1 ? "" : "s"),
                    wait);
        }
        auditLog.recordIndependently("SIGNUP_ATTEMPT", "USER", null, AuditLog.Outcome.SUCCESS,
                Map.of("kind", kind));
    }

    /** Seconds until the oldest counted row ages out of the window, or 0 if under the limit. */
    private long secondsUntilAllowed(String action, String ip, int max, Duration window) {
        List<Long> waits = jdbc.queryForList("""
                SELECT ceil(extract(epoch FROM occurred_at + make_interval(secs => ?) - now()))::bigint
                FROM audit_log
                WHERE action = ?
                  AND ip_address = CAST(? AS inet)
                  AND occurred_at > now() - make_interval(secs => ?)
                ORDER BY occurred_at DESC
                OFFSET ? LIMIT 1
                """, Long.class, window.toSeconds(), action, ip, window.toSeconds(), max - 1);
        return waits.isEmpty() ? 0 : Math.max(1, waits.get(0));
    }

    private static long minutes(long seconds) {
        return (seconds + 59) / 60;
    }
}
