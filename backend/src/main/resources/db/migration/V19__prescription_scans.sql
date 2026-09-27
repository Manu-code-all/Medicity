-- =====================================================================
-- V19: The doctor's handwritten prescription, photographed.
--
-- Most doctors in India write by hand. Asking them to type instead is how a
-- platform loses them, so the doctor photographs the slip. An AI model may
-- read the photo into a draft list of medicines, and the doctor corrects and
-- confirms that draft in the usual form. Only the doctor's confirmation
-- issues a prescription: the model's output never reaches a patient or a
-- chemist by itself. The photo stays attached, so the patient and the store
-- can always compare the typed list with what the doctor actually wrote.
--
-- Images live in the database, capped at 5 MB. At this scale that keeps one
-- backup and one access-control path; a larger deployment would move the
-- bytes to object storage and keep this row as the index.
-- =====================================================================

CREATE TABLE prescription_scans (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_id     UUID         NOT NULL REFERENCES appointments (id),
    doctor_id          UUID         NOT NULL REFERENCES doctors (id),
    content_type       VARCHAR(32)  NOT NULL,
    image              BYTEA        NOT NULL,
    sha256             BYTEA        NOT NULL,
    uploaded_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- What the model returned, kept as it came for the record: the draft the
    -- doctor saw can always be compared with what the doctor issued.
    reading            JSONB,
    reading_status     VARCHAR(16)  NOT NULL DEFAULT 'NOT_READ',
    read_at            TIMESTAMPTZ,

    CONSTRAINT scans_content_type CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    CONSTRAINT scans_size         CHECK (octet_length(image) BETWEEN 1 AND 5242880),
    CONSTRAINT scans_sha256       CHECK (octet_length(sha256) = 32),
    CONSTRAINT scans_reading_status CHECK (reading_status IN ('NOT_READ', 'DRAFTED', 'FAILED', 'UNAVAILABLE'))
);

CREATE INDEX idx_scans_appointment ON prescription_scans (appointment_id, uploaded_at DESC);

ALTER TABLE prescriptions ADD COLUMN scan_id UUID REFERENCES prescription_scans (id);
-- One photographed slip issues one prescription.
CREATE UNIQUE INDEX uq_prescription_scan ON prescriptions (scan_id) WHERE scan_id IS NOT NULL;
