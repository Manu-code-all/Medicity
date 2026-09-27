-- =====================================================================
-- V20: Signing in with a mobile number and a one-time code.
--
-- Indian health platforms sign people in by phone first: many patients do
-- not use email, and everyone has a phone. Email and password stay as the
-- alternative.
--
-- users.phone is contact information, free text and not unique, so it cannot
-- identify an account. login_phone is the normalised number (+91XXXXXXXXXX)
-- that can, unique across accounts. Existing accounts get one only when
-- their number normalises cleanly AND no other account shares it: two
-- accounts on one number cannot tell which of them a code is for, so they
-- keep email sign-in until one of them changes it. This also means the
-- migration cannot fail on data it has not seen.
-- =====================================================================

ALTER TABLE users ADD COLUMN login_phone VARCHAR(16);

UPDATE users u
SET login_phone = n.normalised
FROM (
    SELECT id, normalised, count(*) OVER (PARTITION BY normalised) AS sharing
    FROM (
        SELECT id,
               CASE
                   WHEN digits ~ '^[6-9][0-9]{9}$' THEN '+91' || digits
                   WHEN digits ~ '^91[6-9][0-9]{9}$' THEN '+' || digits
               END AS normalised
        FROM (SELECT id, regexp_replace(phone, '[^0-9]', '', 'g') AS digits FROM users WHERE phone IS NOT NULL) d
    ) c
    WHERE normalised IS NOT NULL
) n
WHERE u.id = n.id AND n.sharing = 1;

CREATE UNIQUE INDEX uq_users_login_phone ON users (login_phone);

ALTER TABLE users ADD CONSTRAINT users_login_phone_format
    CHECK (login_phone IS NULL OR login_phone ~ '^\+91[6-9][0-9]{9}$');

-- One row per code sent. The code itself is never stored: only an HMAC of
-- it, keyed by a server secret and bound to this row's id, so a copy of the
-- table does not give away live codes. A code is good for five minutes,
-- five guesses and one sign-in.
CREATE TABLE otp_challenges (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Issue order. Two codes can share a timestamp; "the newest code" must
    -- still be exactly one of them.
    seq          BIGINT       GENERATED ALWAYS AS IDENTITY,
    user_id      UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    code_hash    VARCHAR(64)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    attempts     SMALLINT     NOT NULL DEFAULT 0,
    consumed_at  TIMESTAMPTZ,

    CONSTRAINT otp_attempts_capped CHECK (attempts BETWEEN 0 AND 5),
    CONSTRAINT otp_expiry_after_creation CHECK (expires_at > created_at)
);

CREATE INDEX idx_otp_challenges_user ON otp_challenges (user_id, seq DESC);
