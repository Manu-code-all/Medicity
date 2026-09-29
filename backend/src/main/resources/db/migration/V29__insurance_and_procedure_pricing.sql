-- Which insurance a doctor's clinic accepts, and what things cost there.
--
-- Insurers are reference data: a fixed list here, so a filter value is
-- always one a doctor can have, and a typo cannot create a new "insurer".
-- Accepting is a claim the clinic makes (cashless or reimbursed at the
-- desk); Medicity does not check a patient's policy.
CREATE TABLE insurers (
    name  VARCHAR(64) PRIMARY KEY,
    kind  VARCHAR(16) NOT NULL,
    CONSTRAINT insurers_kind CHECK (kind IN ('PRIVATE', 'PUBLIC', 'GOVERNMENT'))
);

INSERT INTO insurers (name, kind) VALUES
  ('Star Health',                'PRIVATE'),
  ('Care Health',                'PRIVATE'),
  ('HDFC ERGO',                  'PRIVATE'),
  ('ICICI Lombard',              'PRIVATE'),
  ('Niva Bupa',                  'PRIVATE'),
  ('New India Assurance',        'PUBLIC'),
  ('CGHS',                       'GOVERNMENT'),
  ('Ayushman Bharat (PM-JAY)',   'GOVERNMENT');

CREATE TABLE doctor_insurance (
    doctor_id  UUID        NOT NULL REFERENCES doctors (id) ON DELETE CASCADE,
    insurer    VARCHAR(64) NOT NULL REFERENCES insurers (name),
    PRIMARY KEY (doctor_id, insurer)
);

-- The directory filters by insurer.
CREATE INDEX idx_doctor_insurance_insurer ON doctor_insurance (insurer, doctor_id);

-- Charges beyond the consultation fee (which stays on doctors). "Every
-- visit" charges (a registration fee) are added to the fee the patient
-- sees; the others are shown as a price list.
CREATE TABLE doctor_procedure_prices (
    id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    doctor_id    UUID          NOT NULL REFERENCES doctors (id) ON DELETE CASCADE,
    procedure    VARCHAR(80)   NOT NULL,
    price_inr    INTEGER       NOT NULL,
    every_visit  BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT procedure_price_positive CHECK (price_inr >= 0),
    CONSTRAINT uq_doctor_procedure UNIQUE (doctor_id, procedure)
);
