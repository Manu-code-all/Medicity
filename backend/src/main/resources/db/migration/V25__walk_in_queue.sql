-- Walk-in queue: same-day tokens (#101, #102, ...) per doctor per day, in
-- India time. The day row holds the counter: a join increments it with
-- UPDATE ... RETURNING, which locks that one row for the instant of the
-- increment, so two patients joining together get consecutive numbers and
-- never the same one. The unique index below is the backstop.
CREATE TABLE queue_days (
    doctor_id   UUID        NOT NULL REFERENCES doctors (id) ON DELETE CASCADE,
    queue_date  DATE        NOT NULL,
    last_token  INTEGER     NOT NULL DEFAULT 100,
    closed_at   TIMESTAMPTZ,
    PRIMARY KEY (doctor_id, queue_date)
);

CREATE TABLE queue_tokens (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    doctor_id   UUID         NOT NULL,
    queue_date  DATE         NOT NULL,
    token_no    INTEGER      NOT NULL,
    patient_id  UUID         NOT NULL REFERENCES patients (id) ON DELETE CASCADE,
    status      VARCHAR(16)  NOT NULL DEFAULT 'WAITING',
    reason      VARCHAR(300),
    joined_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    called_at   TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    FOREIGN KEY (doctor_id, queue_date) REFERENCES queue_days (doctor_id, queue_date) ON DELETE CASCADE,
    CONSTRAINT queue_tokens_status CHECK (status IN ('WAITING', 'CALLED', 'SEEN', 'MISSED', 'LEFT'))
);

CREATE UNIQUE INDEX uq_queue_token_number ON queue_tokens (doctor_id, queue_date, token_no);

-- One place in a doctor's line per patient per day.
CREATE UNIQUE INDEX uq_queue_one_active_per_patient
    ON queue_tokens (doctor_id, queue_date, patient_id)
    WHERE status IN ('WAITING', 'CALLED');

CREATE INDEX idx_queue_tokens_patient ON queue_tokens (patient_id, queue_date);
