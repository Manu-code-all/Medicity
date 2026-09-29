-- "Tell me if a time opens with this doctor on this day." One row per
-- doctor, patient (family members included) and day; joining again after
-- leaving reuses it. When a visit that day is cancelled or moved, every
-- entry still waiting is notified through the outbox, in the same
-- transaction as the cancellation. The first to book wins, as with any
-- booking: the unique index on active appointments decides.
CREATE TABLE slot_waitlist (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    doctor_id    UUID         NOT NULL REFERENCES doctors (id) ON DELETE CASCADE,
    patient_id   UUID         NOT NULL REFERENCES patients (id) ON DELETE CASCADE,
    target_date  DATE         NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    notified_at  TIMESTAMPTZ,
    CONSTRAINT slot_waitlist_status CHECK (status IN ('ACTIVE', 'NOTIFIED', 'FULFILLED', 'LEFT')),
    CONSTRAINT uq_slot_waitlist UNIQUE (doctor_id, patient_id, target_date)
);

-- The cancellation path asks: who is waiting for this doctor on this day?
CREATE INDEX idx_slot_waitlist_waiting ON slot_waitlist (doctor_id, target_date)
    WHERE status IN ('ACTIVE', 'NOTIFIED');
