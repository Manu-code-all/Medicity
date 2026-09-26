-- =====================================================================
-- V7: Dispensing — a prescription is filled at most once.
--
-- Same shape of problem as slot booking: two pharmacy counters open the
-- same prescription and both press "Dispense". Checking "already
-- dispensed?" first leaves a window in which both see "no". A unique
-- constraint on prescription_id closes it: the service inserts this row
-- before touching stock, the second insert fails, and the second counter
-- gets a 409 without any stock having moved.
--
-- Append-only, like the prescription it refers to.
-- =====================================================================

CREATE TABLE prescription_dispensations (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    prescription_id  UUID        NOT NULL,
    dispensed_by     UUID,
    dispensed_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_dispensation_prescription FOREIGN KEY (prescription_id) REFERENCES prescriptions (id),
    CONSTRAINT fk_dispensation_user         FOREIGN KEY (dispensed_by)    REFERENCES users (id),
    CONSTRAINT uq_dispensation_prescription UNIQUE (prescription_id)
);
