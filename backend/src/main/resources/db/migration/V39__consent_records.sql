-- =====================================================================
-- V39: Consent records (India's DPDP Act 2023; Google Play).
--
-- One row each time a person agrees to a policy text: who agreed, to what,
-- which version of it, when, and in what capacity. A new policy version is
-- a new row, never an edit, so the table answers "what had this person
-- agreed to on the day we did X" for as long as the account exists.
--
--  * user_id is the account that clicked. For a family member (a patient row
--    with no sign-in of their own) subject_patient_id names them and
--    capacity says why the account holder could agree for them: as the
--    guardian of a minor, or as the representative of an adult.
--  * Not editable by the application, and kept after an account is deleted
--    (the account row is anonymised, not removed), because a controller has
--    to be able to show the consent it relied on.
--  * ON DELETE CASCADE only matters for tests and for the demo reset, which
--    delete accounts outright; the application never does.
-- =====================================================================

CREATE TABLE consent_records (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- NULL: the account holder's own consent.
    subject_patient_id  UUID         REFERENCES patients (id) ON DELETE CASCADE,
    consent_type        VARCHAR(32)  NOT NULL,
    policy_version      VARCHAR(40)  NOT NULL,
    capacity            VARCHAR(24)  NOT NULL DEFAULT 'SELF',
    -- Where it was captured: SIGN_UP, POLICY_UPDATE or FAMILY_MEMBER_ADDED.
    source              VARCHAR(24)  NOT NULL,
    given_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT consent_type_check CHECK (consent_type IN
        ('TERMS_OF_SERVICE', 'PRIVACY_NOTICE', 'HEALTH_DATA_PROCESSING')),
    CONSTRAINT consent_capacity_check CHECK (capacity IN
        ('SELF', 'GUARDIAN_OF_MINOR', 'REPRESENTATIVE_OF_ADULT')),
    CONSTRAINT consent_source_check CHECK (source IN
        ('SIGN_UP', 'POLICY_UPDATE', 'FAMILY_MEMBER_ADDED')),
    -- Agreeing for someone else always names them; agreeing for oneself never does.
    CONSTRAINT consent_capacity_matches_subject CHECK (
        (capacity = 'SELF') = (subject_patient_id IS NULL))
);

-- Agreeing twice to the same version is one fact. Two partial indexes
-- because a UNIQUE index treats NULLs as distinct.
CREATE UNIQUE INDEX uq_consent_self
    ON consent_records (user_id, consent_type, policy_version)
    WHERE subject_patient_id IS NULL;
CREATE UNIQUE INDEX uq_consent_subject
    ON consent_records (user_id, subject_patient_id, consent_type, policy_version)
    WHERE subject_patient_id IS NOT NULL;

-- "Which version has this account agreed to?"
CREATE INDEX idx_consent_user ON consent_records (user_id, consent_type, given_at DESC);
