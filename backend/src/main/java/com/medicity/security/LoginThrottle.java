package com.medicity.security;

import com.medicity.common.TooManyRequestsException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Limits password guessing against one account: after {@code maxFailures}
 * failed logins for an email within {@code window}, further attempts for that
 * email are refused until the oldest of those failures ages out of the window.
 *
 * <p>The count comes from the {@code LOGIN_FAILED} rows the audit log already
 * holds, not from a separate counter. Those rows are shared by every instance,
 * survive a restart, and cannot be deleted even by the application, so an
 * attacker cannot reset the limit, and there is no second store (Redis) to run.
 *
 * <p>Decisions worth knowing:
 * <ul>
 *   <li><b>Per email, not per IP.</b> Behind Railway's edge every request arrives
 *       from one of a few proxy addresses (see Known gaps in the engineering log),
 *       so a per-IP limit would throttle all users together.</li>
 *   <li><b>Unknown emails are limited exactly like real ones,</b> and the check
 *       runs before the account is looked up, so a 429 reveals nothing about
 *       whether an account exists.</li>
 *   <li><b>The limit is temporary, not a lockout.</b> Anyone can fail logins for
 *       someone else's email and keep them out; a sliding window caps that at
 *       {@code window} after the attacker stops, where a lockout would need an
 *       administrator.</li>
 *   <li><b>Refused attempts are not counted as failures,</b> so hammering while
 *       limited does not extend the limit. They are audited as
 *       {@code LOGIN_THROTTLED}.</li>
 * </ul>
 */
@Component
public class LoginThrottle {

    private final JdbcTemplate jdbc;
    private final int maxFailures;
    private final Duration window;

    public LoginThrottle(JdbcTemplate jdbc,
                         @Value("${medicity.auth.login-throttle.max-failures:5}") int maxFailures,
                         @Value("${medicity.auth.login-throttle.window:PT15M}") Duration window) {
        this.jdbc = jdbc;
        this.maxFailures = maxFailures;
        this.window = window;
    }

    /**
     * @throws TooManyRequestsException if {@code email} has reached the limit
     */
    public void check(String email) {
        // The failure that must age out before another attempt is allowed is
        // the maxFailures-th most recent one inside the window. Database time
        // on both sides, because occurred_at is written with the database's now().
        List<Long> waits = jdbc.queryForList("""
                SELECT ceil(extract(epoch FROM occurred_at + make_interval(secs => ?) - now()))::bigint
                FROM audit_log
                WHERE action = 'LOGIN_FAILED'
                  AND detail ->> 'email' = ?
                  AND occurred_at > now() - make_interval(secs => ?)
                ORDER BY occurred_at DESC
                OFFSET ? LIMIT 1
                """, Long.class, window.toSeconds(), email, window.toSeconds(), maxFailures - 1);

        if (!waits.isEmpty()) {
            long retryAfter = Math.max(1, waits.get(0));
            throw new TooManyRequestsException("TOO_MANY_LOGIN_ATTEMPTS",
                    "Too many failed sign-in attempts for this account. Try again in %d minute%s."
                            .formatted(ceilMinutes(retryAfter), ceilMinutes(retryAfter) == 1 ? "" : "s"),
                    retryAfter);
        }
    }

    private static long ceilMinutes(long seconds) {
        return (seconds + 59) / 60;
    }
}
