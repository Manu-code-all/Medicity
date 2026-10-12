-- =====================================================================
-- V37: A PHARMACIST role, and an index for per-address request limits.
--
-- Dispensing and stock used to need ADMIN, so whoever stood at the
-- pharmacy counter could also read the whole audit log. PHARMACIST can
-- dispense and restock but cannot read the audit log.
--
-- The index serves AddressThrottle, which counts recent LOGIN_FAILED and
-- SIGNUP_ATTEMPT audit rows per client address (the same trick LoginThrottle
-- uses per email, see V10). Without it each login and sign-up would scan
-- the whole audit log.
-- =====================================================================

ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
    CHECK (role IN ('PATIENT', 'DOCTOR', 'ADMIN', 'CHEMIST', 'PHARMACIST'));

CREATE INDEX idx_audit_address_attempts
    ON audit_log (action, ip_address, occurred_at DESC)
    WHERE action IN ('LOGIN_FAILED', 'SIGNUP_ATTEMPT');
