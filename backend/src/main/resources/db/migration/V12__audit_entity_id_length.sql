-- =====================================================================
-- V12: Room for endpoint denials in audit_log.entity_id.
--
-- A role-level denial is recorded with entity_type ENDPOINT and entity_id
-- "METHOD /path". VARCHAR(64) is too short for a path carrying a UUID, such
-- as "POST /api/v1/pharmacy/prescriptions/<uuid>/dispense" (81 characters),
-- so the insert failed. Denials are written best-effort (the 403 must still
-- go out), so the failure was only logged, and those denials were never
-- recorded. Found in CI logs while working on metrics.
--
-- Widening a VARCHAR is a catalog change in PostgreSQL: no table rewrite
-- and no row is touched, so the append-only triggers are not involved.
-- AuditLog also truncates, because the path is chosen by the client.
-- =====================================================================

ALTER TABLE audit_log ALTER COLUMN entity_id TYPE VARCHAR(255);
