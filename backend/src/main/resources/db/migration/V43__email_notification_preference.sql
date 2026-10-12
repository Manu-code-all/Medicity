-- =====================================================================
-- V43: Per-user opt-out of notification emails.
--
-- A row exists only for someone who has chosen. No row means emails are on
-- (the default), so existing accounts need no backfill and the narrow,
-- read-on-every-request users table is untouched.
--
-- The email consumer checks this twice: when it decides to email, and again
-- when it sends, so a person who turns emails off is not emailed by events
-- already queued.
-- =====================================================================

CREATE TABLE user_email_preferences (
    user_id     UUID        PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    enabled     BOOLEAN     NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
