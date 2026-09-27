-- =====================================================================
-- V21: Doctors sign up themselves, and set the hours patients can book.
--
-- Until now every doctor was created by the clinic. A doctor can now
-- register with their medical council and registration number, as on
-- Practo; the account starts unverified, is not listed and cannot be
-- booked until an administrator has checked the number with the council.
--
-- verified_at defaults to now(): a doctor inserted any other way (the clinic,
-- the demo seed) is one the clinic vouches for. Self-registration is the one
-- path that writes NULL, explicitly.
-- =====================================================================

ALTER TABLE doctors
    ADD COLUMN medical_council VARCHAR(80),
    ADD COLUMN qualification   VARCHAR(120),
    ADD COLUMN verified_at     TIMESTAMPTZ DEFAULT now();

-- Existing doctors were all created by the clinic: already filled by the
-- default above. The admin's queue reads only the few unverified rows.
CREATE INDEX idx_doctors_unverified ON doctors (created_at) WHERE verified_at IS NULL;

-- One working window per weekday, in the clinic's time zone (Asia/Kolkata).
-- Slots for the next four weeks are generated from it; a nightly job keeps
-- the four weeks rolling.
CREATE TABLE doctor_hours (
    doctor_id     UUID      NOT NULL REFERENCES doctors (id) ON DELETE CASCADE,
    weekday       SMALLINT  NOT NULL,
    starts_at     TIME      NOT NULL,
    ends_at       TIME      NOT NULL,
    slot_minutes  SMALLINT  NOT NULL,

    PRIMARY KEY (doctor_id, weekday),
    CONSTRAINT hours_weekday_iso CHECK (weekday BETWEEN 1 AND 7),
    CONSTRAINT hours_ordered     CHECK (ends_at > starts_at),
    CONSTRAINT hours_slot_length CHECK (slot_minutes IN (10, 15, 20, 30, 45, 60))
);
