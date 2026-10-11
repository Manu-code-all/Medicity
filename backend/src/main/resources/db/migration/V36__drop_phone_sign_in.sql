-- =====================================================================
-- V36: Sign-in by mobile number is gone; email and password, or a code
-- emailed to the account's address, remain. users.phone stays as contact
-- detail. The code table is shared with emailed codes, so it stays.
-- =====================================================================

ALTER TABLE users DROP CONSTRAINT users_login_phone_format;
DROP INDEX uq_users_login_phone;
ALTER TABLE users DROP COLUMN login_phone;
