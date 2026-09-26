-- =====================================================================
-- V6: Close the TRUNCATE gap in audit-log immutability.
--
-- V5's trigger fires BEFORE UPDATE OR DELETE ... FOR EACH ROW. TRUNCATE
-- removes every row without firing row-level triggers at all, so a single
-- `TRUNCATE audit_log` would have erased the whole trail silently. A
-- statement-level trigger is the only kind PostgreSQL fires on TRUNCATE.
--
-- V5 is not edited: an applied migration is immutable, and changing it
-- would fail Flyway's checksum validation on every existing database.
-- =====================================================================

CREATE TRIGGER trg_audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit_log_is_immutable();
