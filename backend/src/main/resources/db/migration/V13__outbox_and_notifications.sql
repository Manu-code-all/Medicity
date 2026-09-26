-- =====================================================================
-- V13: Transactional outbox, and in-app notifications fed by it.
--
-- A change that must tell someone (a booking, a cancellation, a corrected
-- prescription) writes an outbox event in the same transaction as the
-- change. Either both commit or neither does, so there is never a
-- notification about a booking that rolled back, and never a booking whose
-- notification was lost because the process died after committing.
--
-- A relay delivers events afterwards and marks them published. Delivery is
-- at least once (a crash between delivering and marking means the event
-- is delivered again), so every consumer must be idempotent: here, the
-- unique (event_id, user_id) on notifications turns a second delivery into
-- a no-op.
-- =====================================================================

CREATE TABLE outbox_events (
    id               BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id         UUID         NOT NULL DEFAULT gen_random_uuid(),
    event_type       VARCHAR(48)  NOT NULL,
    aggregate_id     UUID         NOT NULL,
    payload          JSONB        NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at     TIMESTAMPTZ,
    attempts         INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error       VARCHAR(500),
    -- Set when retries are exhausted; the event then waits for a person.
    failed_at        TIMESTAMPTZ,

    CONSTRAINT uq_outbox_event_id UNIQUE (event_id),
    CONSTRAINT outbox_attempts_non_negative CHECK (attempts >= 0)
);

-- The relay's only query: pending events that are due, oldest first.
CREATE INDEX idx_outbox_due ON outbox_events (next_attempt_at, id)
    WHERE published_at IS NULL AND failed_at IS NULL;

CREATE TABLE notifications (
    id          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    event_id    UUID          NOT NULL,
    kind        VARCHAR(48)   NOT NULL,
    title       VARCHAR(200)  NOT NULL,
    body        VARCHAR(1000) NOT NULL,
    link        VARCHAR(200),
    -- The moment the notification is about (a visit's start), formatted by
    -- the browser in the reader's own time zone. The server never guesses it.
    occurs_at   TIMESTAMPTZ,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    read_at     TIMESTAMPTZ,

    CONSTRAINT uq_notification_per_event_user UNIQUE (event_id, user_id)
);

CREATE INDEX idx_notifications_user ON notifications (user_id, created_at DESC);
CREATE INDEX idx_notifications_unread ON notifications (user_id) WHERE read_at IS NULL;
