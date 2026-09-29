-- A visit is in person (the default, and every visit before this) or by
-- video. Video visits are joined in the browser; the server only relays the
-- connection set-up between the two browsers, and the call itself is
-- peer to peer.
ALTER TABLE appointments
    ADD COLUMN visit_type VARCHAR(16) NOT NULL DEFAULT 'IN_PERSON',
    ADD CONSTRAINT appointments_visit_type CHECK (visit_type IN ('IN_PERSON', 'VIDEO'));
