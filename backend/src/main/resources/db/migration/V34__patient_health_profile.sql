-- What a clinic asks before it sees someone: height, weight, allergies, long-term
-- conditions and current medicines; and where the person is, so that distances
-- to clinics and chemists can be worked out without asking every time. All
-- optional: a person can skip every question and fill them in later.
ALTER TABLE patients
    ADD COLUMN height_cm           SMALLINT,
    ADD COLUMN weight_kg           NUMERIC(5, 1),
    ADD COLUMN allergies           TEXT,
    ADD COLUMN chronic_conditions  TEXT,
    ADD COLUMN current_medications TEXT,
    ADD COLUMN home_latitude       DOUBLE PRECISION,
    ADD COLUMN home_longitude      DOUBLE PRECISION,
    ADD CONSTRAINT ck_patients_height CHECK (height_cm IS NULL OR height_cm BETWEEN 30 AND 260),
    ADD CONSTRAINT ck_patients_weight CHECK (weight_kg IS NULL OR weight_kg BETWEEN 1 AND 400),
    ADD CONSTRAINT ck_patients_home CHECK (
        (home_latitude IS NULL AND home_longitude IS NULL)
        OR (home_latitude BETWEEN -90 AND 90 AND home_longitude BETWEEN -180 AND 180));
