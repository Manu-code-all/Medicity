-- =====================================================================
-- V18: Family members on one account.
--
-- A parent books for a child; a son manages his mother's medicines. The
-- family member is a patient like any other (visits, prescriptions,
-- questions to chemists all hang off patients.id), but has no sign-in of
-- their own: they are managed by the account holder, the guardian.
--
-- Every patient row is exactly one of the two, enforced here rather than
-- trusted to the code:
--   * an account holder: user_id set, their name on the users row;
--   * a family member:   guardian_user_id set, their own name on this row.
-- =====================================================================

ALTER TABLE patients ALTER COLUMN user_id DROP NOT NULL;

ALTER TABLE patients
    ADD COLUMN guardian_user_id UUID REFERENCES users (id) ON DELETE CASCADE,
    ADD COLUMN full_name        VARCHAR(120),
    ADD COLUMN relationship     VARCHAR(16);

ALTER TABLE patients
    ADD CONSTRAINT patients_one_kind CHECK ((user_id IS NULL) <> (guardian_user_id IS NULL)),
    ADD CONSTRAINT patients_family_member_named
        CHECK (user_id IS NOT NULL OR (full_name IS NOT NULL AND relationship IS NOT NULL)),
    ADD CONSTRAINT patients_relationship_check
        CHECK (relationship IS NULL OR relationship IN ('PARENT', 'CHILD', 'SPOUSE', 'SIBLING', 'OTHER'));

-- "My family": every lookup of an account's members.
CREATE INDEX idx_patients_guardian ON patients (guardian_user_id) WHERE guardian_user_id IS NOT NULL;
