-- Reports and old prescriptions a patient attaches to an upcoming visit, for
-- the doctor to read beforehand. Kept in the database like the photographed
-- prescriptions (see Known gaps: files belong in object storage at scale),
-- served only through endpoints that check who is asking.
CREATE TABLE appointment_attachments (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_id    UUID          NOT NULL REFERENCES appointments (id) ON DELETE CASCADE,
    uploader_user_id  UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    file_name         VARCHAR(120)  NOT NULL,
    -- Decided from the file's first bytes, not from what the browser claimed.
    content_type      VARCHAR(32)   NOT NULL,
    size_bytes        INTEGER       NOT NULL,
    data              BYTEA         NOT NULL,
    note              VARCHAR(255),
    uploaded_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT attachment_type CHECK (content_type IN ('application/pdf', 'image/jpeg', 'image/png', 'image/webp')),
    CONSTRAINT attachment_size CHECK (size_bytes > 0 AND size_bytes <= 5242880)
);

CREATE INDEX idx_attachments_appointment ON appointment_attachments (appointment_id, uploaded_at);
