-- =====================================================================
-- V14: Neighbourhood chemists, and the doctor's "cheaper brand is OK".
--
-- A chemist is a new kind of account (role CHEMIST) that owns one store.
-- Anyone can register a store, but a store receives nothing until an
-- administrator has checked its drug licence and set verified_at. Until
-- then the account can edit its profile and nothing else, so signing up
-- is not a way to see what patients near you are asking for.
--
-- Distance is computed in SQL (store_distance_m below) rather than with
-- PostGIS: the hosted database does not offer the extension, and at the
-- scale of one city a bounding-box prefilter on an ordinary index plus an
-- exact great-circle distance on the few rows left is fast enough.
-- =====================================================================

ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
    CHECK (role IN ('PATIENT', 'DOCTOR', 'ADMIN', 'CHEMIST'));

CREATE TABLE stores (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id   UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name            VARCHAR(120)  NOT NULL,
    -- The state drug licence (Form 20/21). What an administrator checks.
    licence_number  VARCHAR(40)   NOT NULL,
    phone           VARCHAR(20)   NOT NULL,
    address_line    VARCHAR(200)  NOT NULL,
    city            VARCHAR(80)   NOT NULL,
    latitude        NUMERIC(9, 6) NOT NULL,
    longitude       NUMERIC(9, 6) NOT NULL,
    -- Local wall-clock hours in time_zone. closes_at before opens_at means
    -- the store is open past midnight; open_24h ignores both.
    opens_at        TIME          NOT NULL,
    closes_at       TIME          NOT NULL,
    open_24h        BOOLEAN       NOT NULL DEFAULT FALSE,
    time_zone       VARCHAR(40)   NOT NULL DEFAULT 'Asia/Kolkata',
    -- How long the store keeps reserved medicines aside for a patient.
    hold_hours      SMALLINT      NOT NULL DEFAULT 3,
    verified_at     TIMESTAMPTZ,
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version         BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT stores_latitude_range  CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT stores_longitude_range CHECK (longitude BETWEEN -180 AND 180),
    CONSTRAINT stores_hold_hours      CHECK (hold_hours BETWEEN 2 AND 4),
    CONSTRAINT stores_hours_differ    CHECK (open_24h OR opens_at <> closes_at)
);

-- One store per chemist account, and a licence registers one store.
CREATE UNIQUE INDEX uq_stores_owner   ON stores (owner_user_id);
CREATE UNIQUE INDEX uq_stores_licence ON stores (upper(licence_number));
-- The bounding-box prefilter of every "near me" query. Only stores that can
-- receive requests are indexed.
CREATE INDEX idx_stores_location ON stores (latitude, longitude)
    WHERE active AND verified_at IS NOT NULL;
CREATE INDEX idx_stores_unverified ON stores (created_at) WHERE verified_at IS NULL;

-- Great-circle (haversine) distance in metres. Double precision arguments:
-- the numeric columns convert to it implicitly, while a double bound from
-- Java would not convert to numeric and the call would not resolve. LEAST
-- guards asin against rounding just above 1.
CREATE FUNCTION store_distance_m(lat1 DOUBLE PRECISION, lng1 DOUBLE PRECISION,
                                 lat2 DOUBLE PRECISION, lng2 DOUBLE PRECISION)
RETURNS DOUBLE PRECISION
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT 2 * 6371000 * asin(LEAST(1, sqrt(
        power(sin(radians(lat2 - lat1) / 2), 2)
        + cos(radians(lat1)) * cos(radians(lat2)) * power(sin(radians(lng2 - lng1) / 2), 2))))
$$;


-- The doctor decides, per medicine, whether the chemist may hand over a
-- different brand with the same ingredients and strength. Default no: a
-- substitution the doctor did not allow is a call back to the clinic.
ALTER TABLE prescription_items
    ADD COLUMN substitution_allowed BOOLEAN NOT NULL DEFAULT FALSE;
