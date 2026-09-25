-- =====================================================================
-- V5: Audit log.
--
-- Healthcare data carries a "who looked at what, and when" obligation.
-- Every read of another person's clinical record and every mutation of
-- a clinical entity is recorded here by AuditAspect.
--
-- The table is append-only by policy and enforced by a trigger: there is
-- no application path that updates or deletes an audit row, and the
-- database refuses those statements outright. An attacker with the
-- application's DB credentials still cannot quietly erase their tracks.
-- =====================================================================

CREATE TABLE audit_log (
    id           BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id     UUID,                       -- NULL for unauthenticated attempts
    actor_role   VARCHAR(16),
    action       VARCHAR(48)  NOT NULL,      -- APPOINTMENT_BOOKED, RECORD_VIEWED ...
    entity_type  VARCHAR(48)  NOT NULL,
    entity_id    VARCHAR(64),
    outcome      VARCHAR(16)  NOT NULL,      -- SUCCESS / DENIED / ERROR
    ip_address   INET,
    detail       JSONB,
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT audit_outcome_check CHECK (outcome IN ('SUCCESS','DENIED','ERROR'))
);

CREATE INDEX idx_audit_actor      ON audit_log (actor_id, occurred_at DESC);
CREATE INDEX idx_audit_entity     ON audit_log (entity_type, entity_id, occurred_at DESC);
CREATE INDEX idx_audit_time       ON audit_log (occurred_at DESC);
-- Investigating "show me everything that was denied" should not scan the table.
CREATE INDEX idx_audit_denied     ON audit_log (occurred_at DESC) WHERE outcome = 'DENIED';


-- Enforce append-only at the database level.
CREATE OR REPLACE FUNCTION audit_log_is_immutable()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only; % is not permitted', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_log_no_update
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_is_immutable();
