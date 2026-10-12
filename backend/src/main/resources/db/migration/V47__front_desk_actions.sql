-- =====================================================================
-- V47: What the front desk records on visits and walk-in tokens.
--
--   appointments.arrived_at        : the receptionist marked the patient as
--                                    arrived (NULL until then).
--   appointments.booked_by_user_id : set when staff booked the visit on a
--                                    patient's behalf; NULL for self-service.
--   queue_tokens.added_by_user_id  : set when staff added the walk-in token.
--
-- The audit log keeps the full trail; these let the screens and the report
-- tell desk bookings from self-service ones without reading it.
-- =====================================================================

ALTER TABLE appointments
    ADD COLUMN arrived_at TIMESTAMPTZ,
    ADD COLUMN booked_by_user_id UUID REFERENCES users (id) ON DELETE SET NULL;

ALTER TABLE queue_tokens
    ADD COLUMN added_by_user_id UUID REFERENCES users (id) ON DELETE SET NULL;
