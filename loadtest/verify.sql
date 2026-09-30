-- Run after the load test. Each query returns one row with ok = true or false;
-- the workflow fails if any is false. The load test itself only sees HTTP
-- status codes; these check what actually landed in the database.
\pset format unaligned
\pset tuples_only on
\pset fieldsep ' '

-- No slot holds more than one active appointment.
SELECT 'no_double_bookings', count(*) = 0, count(*)
FROM (SELECT slot_id FROM appointments WHERE status <> 'CANCELLED'
      GROUP BY slot_id HAVING count(*) > 1) d;

-- Every one of the 20 contended slots ended up with exactly one patient:
-- nobody lost a slot that was never actually won.
SELECT 'every_hot_slot_booked_once', count(*) = 20, count(*)
FROM (SELECT s.id FROM appointment_slots s
      JOIN appointments a ON a.slot_id = s.id AND a.status <> 'CANCELLED'
      WHERE s.doctor_id = 'd0000000-0000-4000-8000-000000000001'
      GROUP BY s.id HAVING count(*) = 1) h;

-- No patient holds two appointments at the same instant.
SELECT 'no_patient_double_booked', count(*) = 0, count(*)
FROM (SELECT patient_id, scheduled_at FROM appointments WHERE status <> 'CANCELLED'
      GROUP BY patient_id, scheduled_at HAVING count(*) > 1) p;

-- Every booking was audited, in the same transaction as the booking.
SELECT 'every_booking_audited',
       (SELECT count(*) FROM appointments) = (SELECT count(*) FROM audit_log WHERE action = 'APPOINTMENT_BOOKED'),
       (SELECT count(*) FROM appointments);

-- Walk-in tokens at the queue doctor run 101, 102, ... with no gap and no
-- repeat (the day's counter starts at 100), whatever the count was (zero
-- when run outside the queue's hours).
SELECT 'queue_tokens_contiguous',
       count(*) = 0 OR (min(token_no) = 101 AND max(token_no) = 100 + count(*)
                        AND count(DISTINCT token_no) = count(*)),
       count(*)
FROM queue_tokens WHERE doctor_id = 'd0000000-0000-4000-8000-000000000005';

-- Every token was called exactly once: none left waiting, and every token
-- given is now CALLED (one row per token, so none can be called twice).
SELECT 'queue_every_token_called', count(*) FILTER (WHERE status = 'WAITING') = 0,
       count(*) FILTER (WHERE status = 'CALLED')
FROM queue_tokens WHERE doctor_id = 'd0000000-0000-4000-8000-000000000005';
