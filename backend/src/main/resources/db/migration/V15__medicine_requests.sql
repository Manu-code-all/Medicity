-- =====================================================================
-- V15: "Ask all nearby chemists" — one question, every store answers.
--
-- A patient sends one of their doctor's prescriptions to every verified
-- store within a radius. Each store answers per medicine: yes, partly or
-- no, with its price, optionally offering another brand of the same
-- medicine where the doctor allowed that. The patient compares the answers.
--
-- The question is always a prescription issued in Medicity by the doctor's
-- own account, never an uploaded photo, so the chemist knows it is real.
--
-- Rules the database keeps, because each could race:
--  * one open question per prescription at a time (partial unique index);
--  * a store answers a question once, and only a question it was sent
--    (the answer is an UPDATE of the store's own recipient row, from
--    PENDING, and answer lines reference that row);
--  * an answer line is for a medicine on the question (composite FK) and
--    is internally consistent (CHECKs on availability, quantity and price).
-- =====================================================================

CREATE TABLE medicine_requests (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id       UUID          NOT NULL REFERENCES patients (id),
    prescription_id  UUID          NOT NULL REFERENCES prescriptions (id),
    -- Where the patient asked from. Kept for the "asked for nearby" counts
    -- stores see; never shown to a store.
    latitude         NUMERIC(9, 6) NOT NULL,
    longitude        NUMERIC(9, 6) NOT NULL,
    radius_m         INTEGER       NOT NULL,
    status           VARCHAR(16)   NOT NULL DEFAULT 'OPEN',
    stores_asked     INTEGER       NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- After this, stores stop seeing it: stock answers go stale.
    expires_at       TIMESTAMPTZ   NOT NULL,
    closed_at        TIMESTAMPTZ,

    CONSTRAINT requests_status_check CHECK (status IN ('OPEN', 'RESERVED', 'CLOSED', 'EXPIRED')),
    CONSTRAINT requests_radius_check CHECK (radius_m BETWEEN 100 AND 10000),
    CONSTRAINT requests_stores_asked CHECK (stores_asked > 0),
    CONSTRAINT requests_expiry_after_creation CHECK (expires_at > created_at)
);

-- Asking twice for the same prescription at once would send every store the
-- same question twice. A second question waits until the first is closed.
CREATE UNIQUE INDEX uq_request_active_per_prescription ON medicine_requests (prescription_id)
    WHERE status IN ('OPEN', 'RESERVED');
CREATE INDEX idx_requests_patient ON medicine_requests (patient_id, created_at DESC);
CREATE INDEX idx_requests_open_expiry ON medicine_requests (expires_at) WHERE status = 'OPEN';


-- What is asked for: a snapshot of the prescription's lines.
CREATE TABLE medicine_request_items (
    request_id            UUID    NOT NULL REFERENCES medicine_requests (id) ON DELETE CASCADE,
    medicine_id           UUID    NOT NULL REFERENCES medicines (id),
    quantity              INTEGER NOT NULL,
    substitution_allowed  BOOLEAN NOT NULL,

    PRIMARY KEY (request_id, medicine_id),
    CONSTRAINT request_items_quantity CHECK (quantity > 0)
);


-- One row per store the question went to: that store's queue entry.
CREATE TABLE request_recipients (
    request_id   UUID         NOT NULL REFERENCES medicine_requests (id) ON DELETE CASCADE,
    store_id     UUID         NOT NULL REFERENCES stores (id) ON DELETE CASCADE,
    distance_m   INTEGER      NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    note         VARCHAR(300),
    answered_at  TIMESTAMPTZ,

    PRIMARY KEY (request_id, store_id),
    CONSTRAINT recipients_status_check CHECK (status IN ('PENDING', 'ANSWERED')),
    CONSTRAINT recipients_answered_at CHECK ((status = 'ANSWERED') = (answered_at IS NOT NULL))
);

-- The store's queue: what is still waiting for it.
CREATE INDEX idx_recipients_store ON request_recipients (store_id, status);


-- A store's answer, one line per medicine asked for.
CREATE TABLE request_answer_lines (
    request_id              UUID          NOT NULL,
    store_id                UUID          NOT NULL,
    medicine_id             UUID          NOT NULL,
    availability            VARCHAR(8)    NOT NULL,
    quantity_available      INTEGER       NOT NULL,
    unit_price              NUMERIC(10, 2),
    -- Another brand of the same medicine, offered in place of the one asked
    -- for. Allowed only where the doctor ticked "cheaper brand is OK"; the
    -- service checks that, and that it is the same ingredient and strength.
    substitute_medicine_id  UUID          REFERENCES medicines (id),

    PRIMARY KEY (request_id, store_id, medicine_id),
    FOREIGN KEY (request_id, store_id) REFERENCES request_recipients (request_id, store_id) ON DELETE CASCADE,
    FOREIGN KEY (request_id, medicine_id) REFERENCES medicine_request_items (request_id, medicine_id) ON DELETE CASCADE,
    CONSTRAINT answer_availability_check CHECK (availability IN ('YES', 'PARTIAL', 'NO')),
    -- "No" has nothing to sell and no price; anything else has both.
    CONSTRAINT answer_no_means_nothing CHECK (
        (availability = 'NO' AND quantity_available = 0 AND unit_price IS NULL AND substitute_medicine_id IS NULL)
        OR (availability <> 'NO' AND quantity_available > 0 AND unit_price IS NOT NULL)),
    CONSTRAINT answer_price_non_negative CHECK (unit_price IS NULL OR unit_price >= 0)
);

-- "What was asked for near me, and what did I say?" (the demand view).
CREATE INDEX idx_answer_lines_store ON request_answer_lines (store_id, medicine_id);
