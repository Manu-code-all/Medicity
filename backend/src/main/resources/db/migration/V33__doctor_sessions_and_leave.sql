-- =====================================================================
-- V33: Several sessions a day, and days off.
--
-- Hours were one window per weekday, so a doctor with a lunch break or a
-- morning and an evening clinic could not say so. A weekday may now hold
-- up to three sessions (the service checks they do not overlap); the key
-- becomes (doctor, weekday, start).
--
-- Leave is a list of dates. No slot is opened on them, unbooked slots
-- already open are removed, and the walk-in queue does not take tokens.
-- Visits already booked on a leave day are kept: cancelling a patient's
-- visit is the doctor's decision to make, not a side effect of a calendar.
-- =====================================================================

ALTER TABLE doctor_hours DROP CONSTRAINT doctor_hours_pkey;
ALTER TABLE doctor_hours ADD PRIMARY KEY (doctor_id, weekday, starts_at);

CREATE TABLE doctor_leave (
    doctor_id  UUID         NOT NULL REFERENCES doctors (id) ON DELETE CASCADE,
    day        DATE         NOT NULL,
    note       VARCHAR(120),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (doctor_id, day)
);
