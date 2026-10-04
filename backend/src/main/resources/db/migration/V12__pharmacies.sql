-- =====================================================================
-- V12: Pharmacies — the stores a patient can be sent to.
--
-- A pharmacy is a role-specific profile like doctors and patients: the
-- PHARMACIST user authenticates, this row describes the shop. One
-- pharmacist account runs exactly one shop for now.
--
-- Pharmacist accounts are provisioned by an administrator, never by
-- self-registration, for the same reason doctors are: a shop appears in
-- search results shown to patients who are unwell, so someone must have
-- checked the drug licence first. Creating the row IS that check, which
-- is why status starts at ACTIVE and there is no PENDING state.
--
-- "Near me" needs no PostGIS. Railway's Postgres does not promise it, and
-- a few thousand shops do not justify an extension: the search prefilters
-- on a latitude/longitude bounding box (indexed below) and computes the
-- exact great-circle distance only for the rows inside it.
-- =====================================================================

ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
    CHECK (role IN ('PATIENT', 'DOCTOR', 'PHARMACIST', 'ADMIN'));


CREATE TABLE pharmacies (
    id                   UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              UUID             NOT NULL,
    name                 VARCHAR(160)     NOT NULL,
    drug_licence_number  VARCHAR(40)      NOT NULL,
    phone                VARCHAR(20)      NOT NULL,
    address_line         VARCHAR(200)     NOT NULL,
    city                 VARCHAR(80)      NOT NULL,
    pincode              VARCHAR(10),
    latitude             DOUBLE PRECISION NOT NULL,
    longitude            DOUBLE PRECISION NOT NULL,
    -- Local wall-clock hours in `timezone`. A shop that closes after
    -- midnight has closes_at < opens_at; see Pharmacy.isOpenAt.
    opens_at             TIME             NOT NULL DEFAULT '09:00',
    closes_at            TIME             NOT NULL DEFAULT '21:00',
    timezone             VARCHAR(40)      NOT NULL DEFAULT 'Asia/Kolkata',
    -- SUSPENDED shops vanish from search but keep their history.
    status               VARCHAR(16)      NOT NULL DEFAULT 'ACTIVE',
    created_at           TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ      NOT NULL DEFAULT now(),
    version              BIGINT           NOT NULL DEFAULT 0,

    CONSTRAINT fk_pharmacies_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT pharmacies_status_check    CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    -- A swapped or mistyped pair (the classic lat/lng mix-up) puts a shop in the
    -- ocean; the ranges catch the ones that fall outside the globe entirely.
    CONSTRAINT pharmacies_latitude_range  CHECK (latitude  BETWEEN -90  AND 90),
    CONSTRAINT pharmacies_longitude_range CHECK (longitude BETWEEN -180 AND 180),
    CONSTRAINT pharmacies_hours_differ    CHECK (opens_at <> closes_at)
);

-- One shop per pharmacist account, and one account per licence: the same
-- licence on two accounts means one shop is being impersonated.
CREATE UNIQUE INDEX uq_pharmacies_user    ON pharmacies (user_id);
CREATE UNIQUE INDEX uq_pharmacies_licence ON pharmacies (lower(drug_licence_number));

-- Feeds the bounding-box prefilter of the nearby search. Partial, because
-- suspended shops are never searched.
CREATE INDEX idx_pharmacies_location ON pharmacies (latitude, longitude) WHERE status = 'ACTIVE';
