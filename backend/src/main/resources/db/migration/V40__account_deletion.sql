-- =====================================================================
-- V40: Account deletion (DPDP Act erasure right; Google Play).
--
-- Deleting an account anonymises it rather than removing the row, because
-- appointments, prescriptions, dispensations and the audit log reference
-- the user and patient rows and must be kept (see entry 72 of the
-- engineering log for what is kept and why). deleted_at marks an account
-- in that state.
-- =====================================================================

ALTER TABLE users ADD COLUMN deleted_at TIMESTAMPTZ;

-- A deleted account is always disabled: the sign-in path already checks
-- `enabled`, so nothing else has to know about deletion to refuse it.
ALTER TABLE users ADD CONSTRAINT users_deleted_is_disabled
    CHECK (deleted_at IS NULL OR enabled = FALSE);

CREATE INDEX idx_users_deleted ON users (deleted_at) WHERE deleted_at IS NOT NULL;
