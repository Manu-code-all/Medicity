-- Where a doctor sees patients, so the directory can say how far the clinic is
-- from the person looking. Optional: a doctor with no location set simply
-- shows no distance. Both coordinates or neither.
ALTER TABLE doctors
    ADD COLUMN clinic_name      VARCHAR(120),
    ADD COLUMN clinic_address   VARCHAR(200),
    ADD COLUMN clinic_latitude  DOUBLE PRECISION,
    ADD COLUMN clinic_longitude DOUBLE PRECISION,
    ADD CONSTRAINT ck_doctors_clinic_location CHECK (
        (clinic_latitude IS NULL AND clinic_longitude IS NULL)
        OR (clinic_latitude BETWEEN -90 AND 90 AND clinic_longitude BETWEEN -180 AND 180));
