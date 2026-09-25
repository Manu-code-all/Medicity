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
  ('cccccccc-3333-4333-8333-cccccccccc06', 'Omeprazole', 'Omeprazole', 'Generic Pharma', 'CAPSULE', '20mg', 55.00, true)
ON CONFLICT DO NOTHING;


INSERT INTO medicine_stock (medicine_id, quantity_on_hand, reorder_level)
SELECT id, 250, 40 FROM medicines
ON CONFLICT (medicine_id) DO NOTHING;
