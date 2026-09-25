-- =====================================================================
-- V1: Core identity — users and the two role-specific profiles.
--
-- Design notes:
--  * `users` is the single authentication principal. A person is exactly
--    one of PATIENT / DOCTOR / ADMIN; role-specific attributes live in
--    side tables so the auth table stays narrow and hot.
--  * Emails are stored lowercased (enforced by CHECK) so the unique index
--    is a true case-insensitive constraint without needing citext.
--  * `version` supports JPA optimistic locking on mutable rows.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE users (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(254) NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    full_name       VARCHAR(120) NOT NULL,
    phone           VARCHAR(20),
    role            VARCHAR(16)  NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT users_role_check  CHECK (role IN ('PATIENT', 'DOCTOR', 'ADMIN')),
    CONSTRAINT users_email_lower  CHECK (email = lower(email)),
    CONSTRAINT users_email_format CHECK (email LIKE '%_@_%._%')
);

-- Case-insensitive uniqueness: emails are normalised to lowercase on write.
CREATE UNIQUE INDEX uq_users_email ON users (email);
CREATE INDEX idx_users_role ON users (role) WHERE enabled = TRUE;


CREATE TABLE doctors (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID         NOT NULL,
    specialization     VARCHAR(80)  NOT NULL,
    license_number     VARCHAR(40)  NOT NULL,
    consultation_fee   NUMERIC(10,2) NOT NULL,
    years_experience   INTEGER      NOT NULL DEFAULT 0,
    bio                TEXT,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version            BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_doctors_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT doctors_fee_positive CHECK (consultation_fee >= 0),
    CONSTRAINT doctors_experience_sane CHECK (years_experience BETWEEN 0 AND 70)
);

-- One doctor profile per user account.
CREATE UNIQUE INDEX uq_doctors_user    ON doctors (user_id);
CREATE UNIQUE INDEX uq_doctors_license ON doctors (license_number);
CREATE INDEX idx_doctors_specialization ON doctors (specialization);


CREATE TABLE patients (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID        NOT NULL,
    date_of_birth    DATE        NOT NULL,
    gender           VARCHAR(16) NOT NULL,
    blood_group      VARCHAR(3),
    address_line     VARCHAR(200),
    city             VARCHAR(80),
    emergency_contact VARCHAR(20),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version          BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT fk_patients_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT patients_gender_check CHECK (gender IN ('MALE', 'FEMALE', 'OTHER', 'UNDISCLOSED')),
    CONSTRAINT patients_dob_past     CHECK (date_of_birth <= CURRENT_DATE),
    CONSTRAINT patients_blood_group_check
        CHECK (blood_group IS NULL OR blood_group IN ('A+','A-','B+','B-','AB+','AB-','O+','O-'))
);

CREATE UNIQUE INDEX uq_patients_user ON patients (user_id);
