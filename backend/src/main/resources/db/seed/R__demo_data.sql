-- =====================================================================
-- Demo data. Loaded ONLY under the "demo" Spring profile, which adds
-- classpath:db/seed to the Flyway locations. A deployed environment never
-- sees this file, so there is no path by which these accounts reach
-- production.
--
-- This is a REPEATABLE migration (R__): Flyway re-applies it whenever its
-- checksum changes, and every statement is idempotent, so editing the seed
-- does not require wiping the database.
--
-- Password for every demo account: demo-password-2026
-- (BCrypt cost 12, hashed below.)
-- =====================================================================

-- Fixed UUIDs so the seed is stable across runs and slots can reference
-- doctors without a lookup round-trip.
INSERT INTO users (id, email, password_hash, full_name, phone, role) VALUES
  ('11111111-1111-4111-8111-111111111101', 'dr.rao@medicity.demo',
   '$2b$12$GNPEonEpiZzkCXawhbg.W.3zOdBWM3QOSPaxGHlQB0aaZD8xuHrve',
   'Dr. Anjali Rao', '+919876500001', 'DOCTOR'),
  ('11111111-1111-4111-8111-111111111102', 'dr.iyer@medicity.demo',
   '$2b$12$GNPEonEpiZzkCXawhbg.W.3zOdBWM3QOSPaxGHlQB0aaZD8xuHrve',
   'Dr. Suresh Iyer', '+919876500002', 'DOCTOR'),
  ('11111111-1111-4111-8111-111111111103', 'dr.khan@medicity.demo',
   '$2b$12$GNPEonEpiZzkCXawhbg.W.3zOdBWM3QOSPaxGHlQB0aaZD8xuHrve',
   'Dr. Farah Khan', '+919876500003', 'DOCTOR'),
  ('22222222-2222-4222-8222-222222222201', 'patient@medicity.demo',
   '$2b$12$GNPEonEpiZzkCXawhbg.W.3zOdBWM3QOSPaxGHlQB0aaZD8xuHrve',
   'Meera Nair', '+919876500101', 'PATIENT'),
  ('33333333-3333-4333-8333-333333333301', 'admin@medicity.demo',
   '$2b$12$GNPEonEpiZzkCXawhbg.W.3zOdBWM3QOSPaxGHlQB0aaZD8xuHrve',
   'Ops Admin', '+919876500201', 'ADMIN')
ON CONFLICT (email) DO NOTHING;


INSERT INTO doctors (id, user_id, specialization, license_number, consultation_fee, years_experience, bio) VALUES
  ('aaaaaaaa-1111-4111-8111-aaaaaaaaaa01', '11111111-1111-4111-8111-111111111101',
   'Cardiology', 'KA-CARD-10041', 1200.00, 14,
   'Interventional cardiologist with a focus on preventive care and arrhythmia management.'),
  ('aaaaaaaa-1111-4111-8111-aaaaaaaaaa02', '11111111-1111-4111-8111-111111111102',
   'Neurology', 'KA-NEUR-20088', 1500.00, 18,
   'Consultant neurologist specialising in epilepsy and movement disorders.'),
  ('aaaaaaaa-1111-4111-8111-aaaaaaaaaa03', '11111111-1111-4111-8111-111111111103',
   'Paediatrics', 'KA-PAED-30172', 800.00, 9,
   'Paediatrician with an interest in neonatal care and childhood immunisation.')
ON CONFLICT (license_number) DO NOTHING;


INSERT INTO patients (id, user_id, date_of_birth, gender, blood_group, city) VALUES
  ('bbbbbbbb-2222-4222-8222-bbbbbbbbbb01', '22222222-2222-4222-8222-222222222201',
   '1994-08-12', 'FEMALE', 'O+', 'Bengaluru')
ON CONFLICT (user_id) DO NOTHING;


-- Generates 30-minute slots, 10:00-13:00 local, for the next 14 days, for
-- every seeded doctor. Weekends are skipped. Doing this in SQL rather than
-- as a literal list keeps the demo useful however long after seeding it runs.
INSERT INTO appointment_slots (doctor_id, starts_at, ends_at, status)
SELECT d.id,
       slot_start,
       slot_start + INTERVAL '30 minutes',
       'OPEN'
FROM doctors d
CROSS JOIN LATERAL (
    SELECT generate_series(
        date_trunc('day', now()) + INTERVAL '1 day' + INTERVAL '10 hours',
        date_trunc('day', now()) + INTERVAL '14 days' + INTERVAL '13 hours',
        INTERVAL '30 minutes'
    ) AS slot_start
) s
WHERE EXTRACT(HOUR FROM slot_start) BETWEEN 10 AND 12
  AND EXTRACT(ISODOW FROM slot_start) < 6
-- The EXCLUDE constraint would reject a duplicate anyway; skipping them here
-- keeps re-running the seed silent instead of noisy.
ON CONFLICT DO NOTHING;


INSERT INTO medicines (id, name, generic_name, manufacturer, form, strength, unit_price, prescription_required) VALUES
  ('cccccccc-3333-4333-8333-cccccccccc01', 'Paracetamol', 'Acetaminophen', 'Generic Pharma', 'TABLET', '500mg', 40.00, false),
  ('cccccccc-3333-4333-8333-cccccccccc02', 'Azithromycin', 'Azithromycin', 'Generic Pharma', 'TABLET', '250mg', 75.00, true),
  ('cccccccc-3333-4333-8333-cccccccccc03', 'Cetirizine', 'Cetirizine', 'Generic Pharma', 'TABLET', '10mg', 30.00, false),
  ('cccccccc-3333-4333-8333-cccccccccc04', 'Amoxicillin', 'Amoxicillin', 'Generic Pharma', 'CAPSULE', '500mg', 90.00, true),
  ('cccccccc-3333-4333-8333-cccccccccc05', 'Metformin', 'Metformin HCl', 'Generic Pharma', 'TABLET', '500mg', 50.00, true),
  ('cccccccc-3333-4333-8333-cccccccccc06', 'Omeprazole', 'Omeprazole', 'Generic Pharma', 'CAPSULE', '20mg', 55.00, true),
  -- Other brands of the same medicines, same strength: what a chemist may
  -- offer instead when the doctor allowed a substitution.
  ('cccccccc-3333-4333-8333-cccccccccc07', 'Crocin', 'Acetaminophen', 'GSK', 'TABLET', '500mg', 30.00, false),
  ('cccccccc-3333-4333-8333-cccccccccc08', 'Azee', 'Azithromycin', 'Cipla', 'TABLET', '250mg', 68.00, true),
  ('cccccccc-3333-4333-8333-cccccccccc09', 'Glycomet', 'Metformin HCl', 'USV', 'TABLET', '500mg', 38.00, true),
  ('cccccccc-3333-4333-8333-cccccccccc10', 'Omez', 'Omeprazole', 'Dr. Reddy''s', 'CAPSULE', '20mg', 42.00, true)
ON CONFLICT DO NOTHING;


INSERT INTO medicine_stock (medicine_id, quantity_on_hand, reorder_level)
SELECT id, 250, 40 FROM medicines
ON CONFLICT (medicine_id) DO NOTHING;


-- ---------------------------------------------------------------------
-- A believable history for the demo patient, so the portal has something
-- to show: completed visits with prescriptions, a cancellation, a missed
-- visit, one prescription that was later corrected, and a visit coming up.
--
-- Dates are relative to now() so the history always reads as recent. Fixed
-- ids make every row insert exactly once; on a re-run each INSERT is a no-op.
-- Past slots sit at 05:00 UTC (10:30 IST), outside the 10:00-13:00 UTC window
-- the generator above fills, so they cannot collide with it.
-- ---------------------------------------------------------------------
INSERT INTO appointment_slots (id, doctor_id, starts_at, ends_at, status) VALUES
  ('dddddddd-4444-4444-8444-dddddddddd01', 'aaaaaaaa-1111-4111-8111-aaaaaaaaaa01',
   date_trunc('day', now()) - INTERVAL '124 days' + INTERVAL '5 hours',
   date_trunc('day', now()) - INTERVAL '124 days' + INTERVAL '5 hours 30 minutes', 'OPEN'),
  ('dddddddd-4444-4444-8444-dddddddddd02', 'aaaaaaaa-1111-4111-8111-aaaaaaaaaa02',
   date_trunc('day', now()) - INTERVAL '61 days' + INTERVAL '5 hours',
   date_trunc('day', now()) - INTERVAL '61 days' + INTERVAL '5 hours 30 minutes', 'OPEN'),
  ('dddddddd-4444-4444-8444-dddddddddd03', 'aaaaaaaa-1111-4111-8111-aaaaaaaaaa02',
   date_trunc('day', now()) - INTERVAL '33 days' + INTERVAL '5 hours',
   date_trunc('day', now()) - INTERVAL '33 days' + INTERVAL '5 hours 30 minutes', 'OPEN'),
  ('dddddddd-4444-4444-8444-dddddddddd04', 'aaaaaaaa-1111-4111-8111-aaaaaaaaaa01',
   date_trunc('day', now()) - INTERVAL '19 days' + INTERVAL '5 hours',
   date_trunc('day', now()) - INTERVAL '19 days' + INTERVAL '5 hours 30 minutes', 'OPEN'),
  ('dddddddd-4444-4444-8444-dddddddddd05', 'aaaaaaaa-1111-4111-8111-aaaaaaaaaa01',
   date_trunc('day', now()) - INTERVAL '12 days' + INTERVAL '5 hours',
   date_trunc('day', now()) - INTERVAL '12 days' + INTERVAL '5 hours 30 minutes', 'OPEN')
ON CONFLICT DO NOTHING;


INSERT INTO appointments (id, slot_id, patient_id, status, reason, scheduled_at, cancelled_at, cancel_reason)
SELECT v.id::uuid, s.id, 'bbbbbbbb-2222-4222-8222-bbbbbbbbbb01', v.status, v.reason, s.starts_at,
       CASE WHEN v.status = 'CANCELLED' THEN s.starts_at - INTERVAL '2 days' END,
       v.cancel_reason
FROM (VALUES
  ('eeeeeeee-5555-4555-8555-eeeeeeeeee01', 'dddddddd-4444-4444-8444-dddddddddd01', 'COMPLETED',
   'Palpitations after climbing stairs, some heartburn', NULL),
  ('eeeeeeee-5555-4555-8555-eeeeeeeeee02', 'dddddddd-4444-4444-8444-dddddddddd02', 'COMPLETED',
   'Recurring headaches, worse in the evening', NULL),
  ('eeeeeeee-5555-4555-8555-eeeeeeeeee03', 'dddddddd-4444-4444-8444-dddddddddd03', 'NO_SHOW',
   'Headache follow-up', NULL),
  ('eeeeeeee-5555-4555-8555-eeeeeeeeee04', 'dddddddd-4444-4444-8444-dddddddddd04', 'CANCELLED',
   'Cardiology follow-up', 'Rescheduled by patient'),
  ('eeeeeeee-5555-4555-8555-eeeeeeeeee05', 'dddddddd-4444-4444-8444-dddddddddd05', 'COMPLETED',
   'Cardiology follow-up', NULL)
) AS v (id, slot_id, status, reason, cancel_reason)
JOIN appointment_slots s ON s.id = v.slot_id::uuid
ON CONFLICT DO NOTHING;


-- An upcoming visit, taken from the generated calendar: the first free
-- neurology slot three or more days out.
INSERT INTO appointments (id, slot_id, patient_id, status, reason, scheduled_at)
SELECT 'eeeeeeee-5555-4555-8555-eeeeeeeeee06', s.id, 'bbbbbbbb-2222-4222-8222-bbbbbbbbbb01',
       'BOOKED', 'Six-week headache review', s.starts_at
FROM appointment_slots s
WHERE s.doctor_id = 'aaaaaaaa-1111-4111-8111-aaaaaaaaaa02'
  AND s.status = 'OPEN'
  AND s.starts_at >= date_trunc('day', now()) + INTERVAL '3 days'
  AND NOT EXISTS (SELECT 1 FROM appointments a
                  WHERE a.slot_id = s.id AND a.status <> 'CANCELLED')
ORDER BY s.starts_at
LIMIT 1
ON CONFLICT DO NOTHING;


-- Prescriptions. The neurology one is issued, then corrected an hour later:
-- the correction supersedes it, and the portal shows only the correction.
INSERT INTO prescriptions (id, appointment_id, doctor_id, patient_id, diagnosis, notes, supersedes_id, issued_at)
SELECT v.id::uuid, a.id, s.doctor_id, a.patient_id, v.diagnosis, v.notes, v.supersedes_id::uuid,
       a.scheduled_at + v.after::interval
FROM (VALUES
  ('ffffffff-6666-4666-8666-ffffffffff01', 'eeeeeeee-5555-4555-8555-eeeeeeeeee01',
   'Benign sinus palpitations; gastro-oesophageal reflux',
   'ECG and echo normal. Cut caffeine after noon. Review in 3 months, sooner if palpitations come with dizziness.',
   NULL, '25 minutes'),
  ('ffffffff-6666-4666-8666-ffffffffff02', 'eeeeeeee-5555-4555-8555-eeeeeeeeee02',
   'Tension-type headache',
   'Keep a headache diary. Screen breaks every 45 minutes.',
   NULL, '25 minutes'),
  ('ffffffff-6666-4666-8666-ffffffffff03', 'eeeeeeee-5555-4555-8555-eeeeeeeeee02',
   'Tension-type headache',
   'Revised: paracetamol changed from fixed to as-needed dosing to avoid medication-overuse headache. Keep a headache diary.',
   'ffffffff-6666-4666-8666-ffffffffff02', '85 minutes'),
  ('ffffffff-6666-4666-8666-ffffffffff04', 'eeeeeeee-5555-4555-8555-eeeeeeeeee05',
   'Palpitations resolved; reflux controlled',
   'Stop omeprazole after this course. Annual cardiac check-up recommended.',
   NULL, '20 minutes')
) AS v (id, appointment_id, diagnosis, notes, supersedes_id, after)
JOIN appointments a ON a.id = v.appointment_id::uuid
JOIN appointment_slots s ON s.id = a.slot_id
ON CONFLICT DO NOTHING;


-- The two older current prescriptions were filled at the pharmacy the same
-- day; the most recent one is still waiting to be collected.
INSERT INTO prescription_dispensations (prescription_id, dispensed_at)
SELECT p.id, p.issued_at + INTERVAL '40 minutes'
FROM prescriptions p
WHERE p.id IN ('ffffffff-6666-4666-8666-ffffffffff01', 'ffffffff-6666-4666-8666-ffffffffff03')
ON CONFLICT DO NOTHING;


INSERT INTO prescription_items (prescription_id, medicine_id, dosage, frequency, duration_days, quantity) VALUES
  ('ffffffff-6666-4666-8666-ffffffffff01', 'cccccccc-3333-4333-8333-cccccccccc06',
   '20mg', 'Once daily, before breakfast', 28, 28),
  ('ffffffff-6666-4666-8666-ffffffffff02', 'cccccccc-3333-4333-8333-cccccccccc01',
   '500mg', 'Three times daily', 5, 15),
  ('ffffffff-6666-4666-8666-ffffffffff03', 'cccccccc-3333-4333-8333-cccccccccc01',
   '500mg', 'As needed, at most 3 a day', 10, 15),
  ('ffffffff-6666-4666-8666-ffffffffff04', 'cccccccc-3333-4333-8333-cccccccccc06',
   '20mg', 'Once daily, before breakfast', 14, 14)
ON CONFLICT DO NOTHING;


-- ---------------------------------------------------------------------
-- A working day for the demo doctor (dr.rao@medicity.demo), so the doctor
-- workspace has something to act on: one visit that has already started
-- and is waiting to be closed, and one later in the day. Two more patients
-- keep the calendar from being all one person.
--
-- Times are relative to the hour this seed runs. Fixed ids mean each row is
-- inserted once; on later days these visits simply move into the past,
-- where a doctor can still close them.
-- ---------------------------------------------------------------------
INSERT INTO users (id, email, password_hash, full_name, phone, role) VALUES
  ('22222222-2222-4222-8222-222222222202', 'arjun@medicity.demo',
   '$2b$12$GNPEonEpiZzkCXawhbg.W.3zOdBWM3QOSPaxGHlQB0aaZD8xuHrve',
   'Arjun Mehta', '+919876500102', 'PATIENT'),
  ('22222222-2222-4222-8222-222222222203', 'kavya@medicity.demo',
   '$2b$12$GNPEonEpiZzkCXawhbg.W.3zOdBWM3QOSPaxGHlQB0aaZD8xuHrve',
   'Kavya Reddy', '+919876500103', 'PATIENT')
ON CONFLICT (email) DO NOTHING;

INSERT INTO patients (id, user_id, date_of_birth, gender, blood_group, city) VALUES
  ('bbbbbbbb-2222-4222-8222-bbbbbbbbbb02', '22222222-2222-4222-8222-222222222202',
   '1988-02-03', 'MALE', 'A+', 'Mumbai'),
  ('bbbbbbbb-2222-4222-8222-bbbbbbbbbb03', '22222222-2222-4222-8222-222222222203',
   '2001-11-19', 'FEMALE', 'B+', 'Hyderabad')
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO appointment_slots (id, doctor_id, starts_at, ends_at, status) VALUES
  ('dddddddd-4444-4444-8444-dddddddddd06', 'aaaaaaaa-1111-4111-8111-aaaaaaaaaa01',
   date_trunc('hour', now()) - INTERVAL '2 hours',
   date_trunc('hour', now()) - INTERVAL '1 hour 30 minutes', 'OPEN'),
  ('dddddddd-4444-4444-8444-dddddddddd07', 'aaaaaaaa-1111-4111-8111-aaaaaaaaaa01',
   date_trunc('hour', now()) + INTERVAL '2 hours',
   date_trunc('hour', now()) + INTERVAL '2 hours 30 minutes', 'OPEN')
ON CONFLICT DO NOTHING;

INSERT INTO appointments (id, slot_id, patient_id, status, reason, scheduled_at)
SELECT v.id::uuid, s.id, v.patient_id::uuid, 'BOOKED', v.reason, s.starts_at
FROM (VALUES
  ('eeeeeeee-5555-4555-8555-eeeeeeeeee07', 'dddddddd-4444-4444-8444-dddddddddd06',
   'bbbbbbbb-2222-4222-8222-bbbbbbbbbb02', 'Chest tightness when climbing stairs, 2 weeks'),
  ('eeeeeeee-5555-4555-8555-eeeeeeeeee08', 'dddddddd-4444-4444-8444-dddddddddd07',
   'bbbbbbbb-2222-4222-8222-bbbbbbbbbb03', 'Racing heartbeat after coffee')
) AS v (id, slot_id, patient_id, reason)
JOIN appointment_slots s ON s.id = v.slot_id::uuid
ON CONFLICT DO NOTHING;


-- ---------------------------------------------------------------------
-- Neighbourhood chemists around Indiranagar, Bengaluru, where the demo
-- patient lives. All verified, so they receive patients' questions. Sign in
-- as chemist@medicity.demo for Sri Sai Medicals' view.
-- ---------------------------------------------------------------------
INSERT INTO users (id, email, password_hash, full_name, phone, role) VALUES
  ('44444444-4444-4444-8444-444444444401', 'chemist@medicity.demo',
   '$2b$12$GNPEonEpiZzkCXawhbg.W.3zOdBWM3QOSPaxGHlQB0aaZD8xuHrve',
   'Ravi Kumar', '+919876500301', 'CHEMIST'),
  ('44444444-4444-4444-8444-444444444402', 'greencross@medicity.demo',
   '$2b$12$GNPEonEpiZzkCXawhbg.W.3zOdBWM3QOSPaxGHlQB0aaZD8xuHrve',
   'Fatima Sheikh', '+919876500302', 'CHEMIST'),
  ('44444444-4444-4444-8444-444444444403', 'lakshmi@medicity.demo',
   '$2b$12$GNPEonEpiZzkCXawhbg.W.3zOdBWM3QOSPaxGHlQB0aaZD8xuHrve',
   'Lakshmi Narayan', '+919876500303', 'CHEMIST'),
  ('44444444-4444-4444-8444-444444444404', 'nightingale@medicity.demo',
   '$2b$12$GNPEonEpiZzkCXawhbg.W.3zOdBWM3QOSPaxGHlQB0aaZD8xuHrve',
   'Joseph Thomas', '+919876500304', 'CHEMIST'),
  ('44444444-4444-4444-8444-444444444405', 'citycare@medicity.demo',
   '$2b$12$GNPEonEpiZzkCXawhbg.W.3zOdBWM3QOSPaxGHlQB0aaZD8xuHrve',
   'Anita Desai', '+919876500305', 'CHEMIST')
ON CONFLICT (email) DO NOTHING;

INSERT INTO stores (id, owner_user_id, name, licence_number, phone, address_line, city,
                    latitude, longitude, opens_at, closes_at, open_24h, hold_hours, verified_at) VALUES
  ('55555555-5555-4555-8555-555555555501', '44444444-4444-4444-8444-444444444401',
   'Sri Sai Medicals', 'KA-B1-20/21-DEMO01', '+919876500301', '12, 100 Feet Road, Indiranagar', 'Bengaluru',
   12.974500, 77.640800, '08:00', '22:00', false, 3, now()),
  ('55555555-5555-4555-8555-555555555502', '44444444-4444-4444-8444-444444444402',
   'Green Cross Pharmacy', 'KA-B1-20/21-DEMO02', '+919876500302', '4th Cross, HAL 2nd Stage', 'Bengaluru',
   12.978400, 77.640000, '09:00', '21:00', false, 2, now()),
  ('55555555-5555-4555-8555-555555555503', '44444444-4444-4444-8444-444444444403',
   'Lakshmi Medical Stores', 'KA-B1-20/21-DEMO03', '+919876500303', 'CMH Road, Indiranagar', 'Bengaluru',
   12.966000, 77.648000, '07:30', '23:00', false, 4, now()),
  ('55555555-5555-4555-8555-555555555504', '44444444-4444-4444-8444-444444444404',
   'Nightingale 24x7 Chemists', 'KA-B1-20/21-DEMO04', '+919876500304', 'Old Airport Road, Domlur', 'Bengaluru',
   12.959000, 77.644000, '00:00', '23:59', true, 3, now()),
  ('55555555-5555-4555-8555-555555555505', '44444444-4444-4444-8444-444444444405',
   'CityCare Pharmacy', 'KA-B1-20/21-DEMO05', '+919876500305', 'Old Madras Road, Baiyappanahalli', 'Bengaluru',
   12.990000, 77.660000, '08:00', '20:00', false, 3, now())
ON CONFLICT DO NOTHING;


-- ---------------------------------------------------------------------
-- The demo patient has asked the stores about her reflux prescription
-- (omeprazole, where Dr. Rao allowed another brand). Three stores have
-- answered; Sri Sai Medicals (chemist@medicity.demo) and CityCare have it
-- waiting in their queue. It stays open until the next nightly reset,
-- longer than a real question's six hours, so visitors always find it.
-- ---------------------------------------------------------------------
UPDATE prescription_items SET substitution_allowed = TRUE
WHERE prescription_id = 'ffffffff-6666-4666-8666-ffffffffff04';

INSERT INTO medicine_requests (id, patient_id, prescription_id, latitude, longitude, radius_m, status,
                               stores_asked, created_at, expires_at)
VALUES ('abababab-7777-4777-8777-abababab0001', 'bbbbbbbb-2222-4222-8222-bbbbbbbbbb01',
        'ffffffff-6666-4666-8666-ffffffffff04', 12.971900, 77.641200, 3000, 'OPEN', 5,
        now() - INTERVAL '25 minutes', now() + INTERVAL '23 hours')
ON CONFLICT DO NOTHING;

INSERT INTO medicine_request_items (request_id, medicine_id, quantity, substitution_allowed)
VALUES ('abababab-7777-4777-8777-abababab0001', 'cccccccc-3333-4333-8333-cccccccccc06', 14, TRUE)
ON CONFLICT DO NOTHING;

INSERT INTO request_recipients (request_id, store_id, distance_m, status, note, answered_at)
SELECT 'abababab-7777-4777-8777-abababab0001', s.id,
       round(store_distance_m(12.9719, 77.6412, s.latitude, s.longitude)),
       CASE WHEN v.answered THEN 'ANSWERED' ELSE 'PENDING' END, v.note,
       CASE WHEN v.answered THEN now() - v.ago::interval END
FROM (VALUES
  ('55555555-5555-4555-8555-555555555501', false, NULL, NULL),
  ('55555555-5555-4555-8555-555555555502', true, 'Ready at the counter.', '20 minutes'),
  ('55555555-5555-4555-8555-555555555503', true, 'Omez is the same medicine, and cheaper.', '15 minutes'),
  ('55555555-5555-4555-8555-555555555504', true, 'Rest arrives tomorrow morning.', '10 minutes'),
  ('55555555-5555-4555-8555-555555555505', false, NULL, NULL)
) AS v (store_id, answered, note, ago)
JOIN stores s ON s.id = v.store_id::uuid
ON CONFLICT DO NOTHING;

INSERT INTO request_answer_lines (request_id, store_id, medicine_id, availability, quantity_available,
                                  unit_price, substitute_medicine_id) VALUES
  ('abababab-7777-4777-8777-abababab0001', '55555555-5555-4555-8555-555555555502',
   'cccccccc-3333-4333-8333-cccccccccc06', 'YES', 14, 5.50, NULL),
  ('abababab-7777-4777-8777-abababab0001', '55555555-5555-4555-8555-555555555503',
   'cccccccc-3333-4333-8333-cccccccccc06', 'YES', 14, 4.20, 'cccccccc-3333-4333-8333-cccccccccc10'),
  ('abababab-7777-4777-8777-abababab0001', '55555555-5555-4555-8555-555555555504',
   'cccccccc-3333-4333-8333-cccccccccc06', 'PARTIAL', 10, 5.80, NULL)
ON CONFLICT DO NOTHING;
