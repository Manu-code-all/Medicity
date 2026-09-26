-- =====================================================================
-- V8: A visit has at most one original prescription.
--
-- Doctors can now issue prescriptions through the API. Changing what was
-- prescribed is done by a correction (supersedes_id set), never by issuing
-- a second original: two originals for one visit would give the patient
-- two independent sets of instructions with no record of which replaced
-- which. Two browser tabs submitting at once would otherwise do exactly
-- that, so the rule is a partial unique index rather than a service check.
--
-- Corrections are excluded from the index (supersedes_id IS NOT NULL);
-- uq_presc_supersedes from V3 already keeps their chain linear.
-- =====================================================================

CREATE UNIQUE INDEX uq_presc_original_per_appointment
    ON prescriptions (appointment_id)
    WHERE supersedes_id IS NULL;
