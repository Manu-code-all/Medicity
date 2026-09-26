-- =====================================================================
-- V11: Idempotency keys for retried writes.
--
-- A patient taps "Book", the network drops the response, the app retries.
-- The booking already happened; the database's own constraints stop a
-- second one, but the retry then answers 409 "you already have an
-- appointment at this time" for a booking that succeeded. With a key, the
-- retry gets back the original 201 instead.
--
-- Keys are scoped to the user: two patients who happen to send the same
-- key never collide, and one can never be handed the other's response.
-- request_hash catches a client reusing a key for a different request.
-- =====================================================================

CREATE TABLE idempotency_keys (
    user_id          UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    idempotency_key  VARCHAR(255)  NOT NULL,
    request_hash     BYTEA         NOT NULL,
    -- Null only inside the transaction that claimed the key: the row is
    -- inserted first (so a concurrent duplicate waits on it) and the response
    -- is filled in before commit, so no other transaction ever sees it null.
    response_status  SMALLINT,
    response_body    JSONB,
    created_at       TIMESTAMPTZ   NOT NULL,

    PRIMARY KEY (user_id, idempotency_key),
    CONSTRAINT idempotency_request_hash_length CHECK (octet_length(request_hash) = 32)
);

-- For expiring old keys.
CREATE INDEX idx_idempotency_keys_created ON idempotency_keys (created_at);
