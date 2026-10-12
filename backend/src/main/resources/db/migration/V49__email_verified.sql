-- =====================================================================
-- V49: When an account proved it owns its email address.
--
-- Until now nothing proved it: sign-up accepts any address. Records a clinic
-- imported are only offered to an account whose address was proved by typing
-- a code that was emailed to it (a sign-in code, or the code asked for from
-- the profile page), so someone registering with another person's address
-- cannot be shown, or accept, that person's clinic records.
-- Phone numbers have no such proof (sign-in by mobile was removed, entry 70),
-- so a phone number is never used to link a record to an account.
-- =====================================================================

ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMPTZ;
