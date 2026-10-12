-- =====================================================================
-- V44: Clinics, and the staff who run them.
--
-- Until now Medicity was effectively one clinic: each doctor carried their
-- own clinic name and address (V35). A clinic or hospital that wants to be
-- listed as a whole needs a row of its own, so its doctors, its front desk
-- and its numbers can be kept apart from every other clinic's.
--
--   * clinics           : the organisation, with where it is and its time zone.
--   * clinic_staff      : who works the clinic's admin and front desk. A user
--                         is staff of at most one clinic.
--   * doctors.clinic_id : which clinic a doctor belongs to. NULL for an
--                         independent doctor, who keeps the clinic_* columns
--                         of V35 and is listed exactly as before.
--
-- Every clinic-scoped query in the application filters on a clinic id taken
-- from the caller's clinic_staff row, never from the request.
-- =====================================================================

CREATE TABLE clinics (
    id          UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(120)     NOT NULL,
    address     VARCHAR(200),
    city        VARCHAR(80),
    latitude    DOUBLE PRECISION,
    longitude   DOUBLE PRECISION,
    phone       VARCHAR(20),
    email       VARCHAR(254),
    timezone    VARCHAR(40)      NOT NULL DEFAULT 'Asia/Kolkata',
    active      BOOLEAN          NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ      NOT NULL DEFAULT now(),
    version     BIGINT           NOT NULL DEFAULT 0,

    CONSTRAINT ck_clinics_name CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_clinics_location CHECK (
        (latitude IS NULL AND longitude IS NULL)
        OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180))
);

-- The directory lists active clinics by name.
CREATE INDEX idx_clinics_active_name ON clinics (lower(name)) WHERE active;

CREATE TABLE clinic_staff (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id   UUID         NOT NULL REFERENCES clinics (id) ON DELETE CASCADE,
    user_id     UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    staff_role  VARCHAR(16)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_clinic_staff_role CHECK (staff_role IN ('CLINIC_ADMIN', 'RECEPTIONIST'))
);

-- One clinic per staff account: "my clinic" is a single, unambiguous row.
CREATE UNIQUE INDEX uq_clinic_staff_user ON clinic_staff (user_id);
CREATE INDEX idx_clinic_staff_clinic ON clinic_staff (clinic_id);

ALTER TABLE doctors
    ADD COLUMN clinic_id UUID REFERENCES clinics (id) ON DELETE SET NULL;

-- A clinic's doctors, for the directory filter, the desk and the report.
CREATE INDEX idx_doctors_clinic ON doctors (clinic_id) WHERE clinic_id IS NOT NULL;
