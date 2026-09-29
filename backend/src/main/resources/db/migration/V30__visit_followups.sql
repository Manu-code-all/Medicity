-- Free follow-up questions after a visit: the patient may ask up to three
-- in the seven days after it ended, and the doctor answers. The limit is
-- checked with the visit's row locked, so two questions sent at once cannot
-- both be the third.
CREATE TABLE visit_followups (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Order of writing: two messages in the same microsecond still sort as sent.
    seq             BIGINT        GENERATED ALWAYS AS IDENTITY,
    appointment_id  UUID          NOT NULL REFERENCES appointments (id) ON DELETE CASCADE,
    sender          VARCHAR(8)    NOT NULL,
    author_user_id  UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    body            VARCHAR(1000) NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT visit_followups_sender CHECK (sender IN ('PATIENT', 'DOCTOR')),
    CONSTRAINT visit_followups_body CHECK (length(trim(body)) > 0)
);

CREATE INDEX idx_visit_followups_thread ON visit_followups (appointment_id, seq);
