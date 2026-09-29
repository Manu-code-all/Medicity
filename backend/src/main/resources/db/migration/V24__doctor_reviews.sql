-- Reviews come only from visits that happened. Each review belongs to one
-- completed appointment, and the unique index makes "one review per visit"
-- a fact of the schema rather than a check a second request could slip past.
-- Ratings are aggregated when read, not kept as running totals on doctors,
-- so deleting a visit (the demo's nightly reset does) cannot leave a total
-- that disagrees with the reviews behind it.
CREATE TABLE doctor_reviews (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_id UUID         NOT NULL REFERENCES appointments (id) ON DELETE CASCADE,
    doctor_id      UUID         NOT NULL REFERENCES doctors (id) ON DELETE CASCADE,
    patient_id     UUID         NOT NULL REFERENCES patients (id) ON DELETE CASCADE,
    rating         SMALLINT     NOT NULL,
    comment        VARCHAR(1000),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT doctor_reviews_rating_range CHECK (rating BETWEEN 1 AND 5)
);

CREATE UNIQUE INDEX uq_doctor_reviews_appointment ON doctor_reviews (appointment_id);
CREATE INDEX idx_doctor_reviews_doctor ON doctor_reviews (doctor_id, created_at DESC);
