-- =====================================================================
-- V9: Refresh tokens are stored, single-use, and grouped into families.
--
-- Before this, a refresh token was a signed JWT the server kept no record
-- of: it stayed valid for its whole 7 days however many times it was used,
-- so a stolen one worked for a week and nobody would ever know.
--
-- Now each token is a random value, stored only as a SHA-256 hash. Using
-- it marks it used and issues a successor in the same family (one family
-- per sign-in). A used token presented again means two parties hold it —
-- the user and whoever copied it — and the server cannot tell which is
-- which, so the whole family is revoked and both must sign in again.
--
-- SHA-256 rather than BCrypt: the token is 256 random bits, so there is
-- nothing to brute-force and a slow hash buys nothing, while lookup needs a
-- deterministic hash to find the row by value.
-- =====================================================================

CREATE TABLE refresh_tokens (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Cascade: a token is meaningless without its user.
    user_id     UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id   UUID         NOT NULL,
    token_hash  BYTEA        NOT NULL,
    issued_at   TIMESTAMPTZ  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    used_at     TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ,

    CONSTRAINT uq_refresh_token_hash      UNIQUE (token_hash),
    CONSTRAINT refresh_token_hash_length  CHECK (octet_length(token_hash) = 32),
    CONSTRAINT refresh_expires_after_issue CHECK (expires_at > issued_at)
);

CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_user   ON refresh_tokens (user_id);

-- A family has at most one live token. If a bug ever tried to issue two
-- successors for one token, the family would fork into two sessions that
-- reuse detection could not tie together; the database refuses instead.
CREATE UNIQUE INDEX uq_refresh_one_live_per_family
    ON refresh_tokens (family_id)
    WHERE used_at IS NULL AND revoked_at IS NULL;
