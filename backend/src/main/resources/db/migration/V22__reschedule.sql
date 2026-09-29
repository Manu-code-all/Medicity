-- A visit moved to another time: the old appointment is cancelled and a new
-- one booked in the same transaction. The new row remembers which one it
-- replaced, so the history can say "moved" rather than "cancelled", and a
-- retried request can find the move it already made.
ALTER TABLE appointments
    ADD COLUMN rescheduled_from UUID REFERENCES appointments (id) ON DELETE SET NULL;

-- A visit can be moved once: a second move starts from the new appointment.
CREATE UNIQUE INDEX uq_appointments_rescheduled_from
    ON appointments (rescheduled_from)
    WHERE rescheduled_from IS NOT NULL;
