-- =====================================================================
-- V45: CLINIC_ADMIN and RECEPTIONIST roles.
--
-- CLINIC_ADMIN runs one clinic: its profile, its doctors and receptionists,
-- and its report. RECEPTIONIST works that clinic's front desk. Neither can
-- see anything outside the clinic named by their clinic_staff row (V44).
--
-- Widened the same way V37 added PHARMACIST: drop the check, add it back
-- with the longer list. Migrations already applied are never edited.
-- =====================================================================

ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
    CHECK (role IN ('PATIENT', 'DOCTOR', 'ADMIN', 'CHEMIST', 'PHARMACIST', 'CLINIC_ADMIN', 'RECEPTIONIST'));
