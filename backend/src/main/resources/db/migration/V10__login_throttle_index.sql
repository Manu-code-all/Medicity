-- =====================================================================
-- V10: Index for counting recent failed logins per email.
--
-- Every failed login is already written to audit_log with the attempted
-- email (see AuthService). LoginThrottle counts those rows instead of
-- keeping a second counter: the audit log is shared by every instance,
-- survives restarts, and cannot be reset by an attacker, which is exactly
-- what a login limit needs. Without this index each login would scan the
-- whole audit log.
-- =====================================================================

CREATE INDEX idx_audit_login_failed_email
    ON audit_log ((detail ->> 'email'), occurred_at DESC)
    WHERE action = 'LOGIN_FAILED';
