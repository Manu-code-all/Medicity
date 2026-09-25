-- =====================================================================
-- V3: Clinical records — prescriptions written against an appointment.
--
-- Prescriptions are append-only clinical documents. They are never
-- updated in place once issued: a correction creates a new prescription
-- that supersedes the previous one. This mirrors how real clinical
-- systems handle medico-legal traceability, and it makes the audit
-- trail in V5 meaningful.
-- =====================================================================

CREATE TABLE prescriptions (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_id  UUID        NOT NULL,
    doctor_id       UUID        NOT NULL,
    patient_id      UUID        NOT NULL,
    diagnosis       VARCHAR(500) NOT NULL,
    notes           TEXT,
    -- Points at the prescription this one corrects, if any.
    supersedes_id   UUID,
    issued_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_presc_appointment FOREIGN KEY (appointment_id) REFERENCES appointments (id),
    CONSTRAINT fk_presc_doctor      FOREIGN KEY (doctor_id)      REFERENCES doctors (id),
    CONSTRAINT fk_presc_patient     FOREIGN KEY (patient_id)     REFERENCES patients (id),
    CONSTRAINT fk_presc_supersedes  FOREIGN KEY (supersedes_id)  REFERENCES prescriptions (id),
    CONSTRAINT presc_no_self_supersede CHECK (supersedes_id IS NULL OR supersedes_id <> id)
);

CREATE INDEX idx_presc_patient     ON prescriptions (patient_id, issued_at DESC);
CREATE INDEX idx_presc_appointment ON prescriptions (appointment_id);
-- A given prescription may be corrected only once, keeping the
-- supersession chain linear rather than branching.
CREATE UNIQUE INDEX uq_presc_supersedes ON prescriptions (supersedes_id)
    WHERE supersedes_id IS NOT NULL;


CREATE TABLE prescription_items (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    prescription_id  UUID         NOT NULL,
    medicine_id      UUID         NOT NULL,
    dosage           VARCHAR(80)  NOT NULL,   -- e.g. "500mg"
    frequency        VARCHAR(80)  NOT NULL,   -- e.g. "twice daily after food"
    duration_days    INTEGER      NOT NULL,
    quantity         INTEGER      NOT NULL,

    CONSTRAINT fk_presc_item_presc FOREIGN KEY (prescription_id)
        REFERENCES prescriptions (id) ON DELETE CASCADE,
    CONSTRAINT presc_item_duration_positive CHECK (duration_days BETWEEN 1 AND 365),
    CONSTRAINT presc_item_quantity_positive CHECK (quantity > 0)
);

CREATE INDEX idx_presc_items_presc ON prescription_items (prescription_id);
-- The same medicine must not appear twice on one prescription.
CREATE UNIQUE INDEX uq_presc_item_medicine ON prescription_items (prescription_id, medicine_id);
