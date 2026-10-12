-- =====================================================================
-- V48: Records a clinic brought in from its old files (CSV / Excel).
--
-- These are NOT accounts. A clinic_records row is the clinic's own record of
-- a person: nobody can sign in as it, and the person it describes sees none
-- of it until they have an account, have proved the email address, and have
-- said yes to the link (link_state = 'ACCEPTED').
--
-- owner_kind / owner_id: who holds the record. Today it is always a doctor's
-- user id ('DOCTOR'). It is deliberately NOT a foreign key, so that the same
-- column can later hold a clinic id (a new owner_kind) without a rewrite of
-- this table. The owner's rows are reached through (owner_kind, owner_id).
--
-- phone is stored as digits only (the Indian +91 / leading 0 dropped), email
-- lowercased. Both are the keys for a re-import: the same phone or email
-- updates the existing row instead of adding another.
-- =====================================================================

CREATE TABLE clinic_records (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_kind        VARCHAR(16)  NOT NULL DEFAULT 'DOCTOR',
    owner_id          UUID         NOT NULL,

    full_name         VARCHAR(120) NOT NULL,
    phone             VARCHAR(15),
    email             VARCHAR(254),
    date_of_birth     DATE,
    gender            VARCHAR(16),
    blood_group       VARCHAR(3),
    allergies         TEXT,
    notes             TEXT,

    -- Whether the person this describes has agreed to be linked to their account.
    link_state        VARCHAR(10)  NOT NULL DEFAULT 'UNLINKED',
    linked_user_id    UUID         REFERENCES users (id) ON DELETE SET NULL,
    linked_patient_id UUID         REFERENCES patients (id) ON DELETE SET NULL,
    decided_at        TIMESTAMPTZ,

    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT clinic_records_owner_kind  CHECK (owner_kind IN ('DOCTOR')),
    CONSTRAINT clinic_records_link_state  CHECK (link_state IN ('UNLINKED', 'ACCEPTED', 'DECLINED')),
    CONSTRAINT clinic_records_email_lower CHECK (email IS NULL OR email = lower(email)),
    CONSTRAINT clinic_records_contactable CHECK (email IS NOT NULL OR phone IS NOT NULL),
    CONSTRAINT clinic_records_gender      CHECK (gender IS NULL OR gender IN ('MALE', 'FEMALE', 'OTHER', 'UNDISCLOSED')),
    CONSTRAINT clinic_records_blood_group
        CHECK (blood_group IS NULL OR blood_group IN ('A+','A-','B+','B-','AB+','AB-','O+','O-')),
    CONSTRAINT clinic_records_dob_past    CHECK (date_of_birth IS NULL OR date_of_birth <= CURRENT_DATE)
);

-- The re-import keys: one record per phone and one per email, per owner.
CREATE UNIQUE INDEX uq_clinic_records_owner_email ON clinic_records (owner_kind, owner_id, email) WHERE email IS NOT NULL;
CREATE UNIQUE INDEX uq_clinic_records_owner_phone ON clinic_records (owner_kind, owner_id, phone) WHERE phone IS NOT NULL;

-- "Does any clinic hold a record for this verified email?" on the profile page.
CREATE INDEX idx_clinic_records_email ON clinic_records (email) WHERE email IS NOT NULL;
CREATE INDEX idx_clinic_records_linked_user ON clinic_records (linked_user_id) WHERE linked_user_id IS NOT NULL;
