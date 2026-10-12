-- =====================================================================
-- V46: Accounts a clinic creates for its staff, and patients the front desk
-- records.
--
-- must_change_password: a clinic admin creates a doctor's or receptionist's
-- account with a temporary password. While the flag is set the API refuses
-- everything except changing that password.
--
-- patients.registered_by_clinic_id: someone who phones or walks in with no
-- Medicity account is recorded by the front desk. They are an ordinary
-- patient row behind a disabled account nobody can sign in to (so every
-- constraint and query on patients stays as it was), and this column says
-- which clinic may book and queue them. Another clinic's desk cannot use them.
-- =====================================================================

ALTER TABLE users
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE patients
    ADD COLUMN registered_by_clinic_id UUID REFERENCES clinics (id) ON DELETE SET NULL;

-- A desk's own patients, for its search box.
CREATE INDEX idx_patients_registered_by_clinic
    ON patients (registered_by_clinic_id) WHERE registered_by_clinic_id IS NOT NULL;
