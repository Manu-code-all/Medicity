-- =====================================================================
-- V16: Reserve at one store, pick up with a code.
--
-- The patient picks one store's answer. That store keeps what it has aside
-- for its hold time (2 to 4 hours, the store's setting) and the patient
-- gets a six-digit code. At the counter the chemist types the code in; the
-- code matching is what marks the medicines collected.
--
-- Rules the database keeps:
--  * a question is reserved at one store at a time (partial unique index);
--  * only a store that answered can be reserved at (FK to its recipient row);
--  * collection is one conditional UPDATE: still held, not expired, fewer
--    than five wrong codes, and the code matches. The code is never sent
--    to the store: it proves the person at the counter is the patient.
-- =====================================================================

CREATE TABLE reservations (
    id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id            UUID          NOT NULL,
    store_id              UUID          NOT NULL,
    patient_id            UUID          NOT NULL REFERENCES patients (id),
    pickup_code           CHAR(6)       NOT NULL,
    status                VARCHAR(16)   NOT NULL DEFAULT 'HELD',
    total                 NUMERIC(10, 2) NOT NULL,
    complete              BOOLEAN       NOT NULL,
    wrong_code_attempts   SMALLINT      NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at            TIMESTAMPTZ   NOT NULL,
    collected_at          TIMESTAMPTZ,
    ended_at              TIMESTAMPTZ,

    FOREIGN KEY (request_id, store_id) REFERENCES request_recipients (request_id, store_id) ON DELETE CASCADE,
    CONSTRAINT reservations_status_check CHECK (status IN ('HELD', 'COLLECTED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT reservations_code_digits  CHECK (pickup_code ~ '^[0-9]{6}$'),
    CONSTRAINT reservations_hold_window  CHECK (expires_at > created_at
                                                AND expires_at <= created_at + INTERVAL '4 hours'),
    CONSTRAINT reservations_collected_at CHECK ((status = 'COLLECTED') = (collected_at IS NOT NULL)),
    CONSTRAINT reservations_attempts     CHECK (wrong_code_attempts BETWEEN 0 AND 5),
    CONSTRAINT reservations_total        CHECK (total >= 0)
);

-- One live reservation per question: two taps on "Reserve" at two stores
-- cannot both hold medicines for the same prescription.
CREATE UNIQUE INDEX uq_reservation_live_per_request ON reservations (request_id)
    WHERE status IN ('HELD', 'COLLECTED');
-- The store's pick-up list, and the expiry job.
CREATE INDEX idx_reservations_store_held ON reservations (store_id, expires_at) WHERE status = 'HELD';
CREATE INDEX idx_reservations_patient ON reservations (patient_id, created_at DESC);
