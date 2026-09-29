-- What the patient told the body guide before booking (the area, the
-- symptoms ticked, since when, and the kind of doctor it suggested),
-- shared with the doctor only when the patient chose to attach it. JSON
-- because it is a snapshot shown as it was, never queried field by field.
-- The patient's own words stay in appointments.reason.
ALTER TABLE appointments ADD COLUMN intake_summary JSONB;
