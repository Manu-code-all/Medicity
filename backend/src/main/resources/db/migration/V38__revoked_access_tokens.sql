-- =====================================================================
-- V38: Denylist of access tokens revoked before they expired.
--
-- Access tokens are stateless JWTs, so signing out could only stop
-- refreshes; the access token already issued kept working for up to 15
-- minutes. Every access token now carries a unique id (the "jti" claim);
-- signing out records it here, and the authentication filter refuses any
-- token listed. expires_at is the token's own expiry: after it the token
-- is refused anyway, so HousekeepingJob deletes the row.
-- =====================================================================

CREATE TABLE revoked_access_tokens (
    token_id   UUID        PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_revoked_access_tokens_expiry ON revoked_access_tokens (expires_at);
