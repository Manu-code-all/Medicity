-- =====================================================================
-- V2: Scheduling — the concurrency-critical part of the system.
--
-- The invariant we must never violate:
--     "A bookable slot holds at most one active appointment."
--
-- This is enforced in the DATABASE, not in application code. Application
-- checks ("select then insert") are useless under concurrency: two
-- requests can both read `available` before either writes. The only
-- reliable arbiter is a constraint the database evaluates atomically.
--
-- Two constraints do the work here:
--
--  1. `no_overlapping_slots_per_doctor` (EXCLUDE ... USING gist)
--     stops an admin from creating two slots for the same doctor whose
--     time ranges overlap. Without it, a doctor could be double-booked
--     across two technically-distinct slots.
--
--  2. `uq_active_appointment_per_slot` (PARTIAL unique index)
--     is the double-booking guard. A plain UNIQUE(slot_id) would also
--     work, but would permanently burn the slot once an appointment is
--     cancelled. Scoping uniqueness to non-cancelled rows means a
--     cancelled booking frees the slot for reuse while still allowing
--     exactly one active holder at any instant.
--
-- Under concurrent inserts, Postgres blocks the second transaction on
-- the index and then raises unique_violation. The service layer
-- translates that into a domain-level "slot already taken" response.
-- See SlotBookingConcurrencyTest for the proof.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS "btree_gist";


CREATE TABLE appointment_slots (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    doctor_id   UUID        NOT NULL,
    starts_at   TIMESTAMPTZ NOT NULL,
    ends_at     TIMESTAMPTZ NOT NULL,
    -- OPEN slots accept bookings; BLOCKED is an admin/doctor hold
    -- (leave, surgery, conference) that must never be bookable.
    status      VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version     BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT fk_slots_doctor FOREIGN KEY (doctor_id) REFERENCES doctors (id) ON DELETE CASCADE,
    CONSTRAINT slots_status_check CHECK (status IN ('OPEN', 'BLOCKED')),
    CONSTRAINT slots_time_ordered CHECK (ends_at > starts_at),
    -- Guard against fat-fingered admin input creating absurd slots.
    CONSTRAINT slots_duration_sane
        CHECK (ends_at - starts_at BETWEEN INTERVAL '5 minutes' AND INTERVAL '4 hours'),

    -- A single doctor cannot have two slots covering the same instant.
    -- '[)' = half-open range, so 10:00-10:30 and 10:30-11:00 do NOT
    -- conflict, which is exactly what back-to-back consultations need.
    CONSTRAINT no_overlapping_slots_per_doctor
        EXCLUDE USING gist (
            doctor_id WITH =,
            tstzrange(starts_at, ends_at, '[)') WITH &&
        )
);

-- Drives the "find me open slots for Dr. X next week" query.
CREATE INDEX idx_slots_doctor_time ON appointment_slots (doctor_id, starts_at);
-- Partial index: the availability search only ever looks at OPEN slots.
CREATE INDEX idx_slots_open_upcoming
    ON appointment_slots (starts_at)
    WHERE status = 'OPEN';


CREATE TABLE appointments (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    slot_id      UUID        NOT NULL,
    patient_id   UUID        NOT NULL,
    status       VARCHAR(16) NOT NULL DEFAULT 'BOOKED',
    reason       VARCHAR(500),
    -- Denormalised from the slot so the patient's appointment list can be
    -- served and sorted without joining scheduling tables. Kept in sync by
    -- the booking service; slots are immutable in time once created.
    scheduled_at TIMESTAMPTZ NOT NULL,
    cancelled_at TIMESTAMPTZ,
    cancel_reason VARCHAR(300),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    version      BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT fk_appointments_slot    FOREIGN KEY (slot_id)    REFERENCES appointment_slots (id),
    CONSTRAINT fk_appointments_patient FOREIGN KEY (patient_id) REFERENCES patients (id),
    CONSTRAINT appointments_status_check
        CHECK (status IN ('BOOKED', 'COMPLETED', 'CANCELLED', 'NO_SHOW')),
    -- A cancellation timestamp exists if and only if the row is cancelled.
    CONSTRAINT appointments_cancel_consistency
        CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);

-- >>> THE DOUBLE-BOOKING GUARD <<<
-- Exactly one non-cancelled appointment may reference a given slot.
CREATE UNIQUE INDEX uq_active_appointment_per_slot
    ON appointments (slot_id)
    WHERE status <> 'CANCELLED';

-- A patient should not hold two active appointments at the same instant,
-- even with different doctors.
CREATE UNIQUE INDEX uq_patient_active_at_time
    ON appointments (patient_id, scheduled_at)
    WHERE status IN ('BOOKED', 'COMPLETED');

CREATE INDEX idx_appointments_patient  ON appointments (patient_id, scheduled_at DESC);
CREATE INDEX idx_appointments_slot     ON appointments (slot_id);
