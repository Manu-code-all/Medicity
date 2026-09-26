-- =====================================================================
-- Load-test data, loaded by .github/workflows/load-test.yml into a
-- throwaway database after Flyway has migrated it. Never run against a
-- real environment.
--
--   * 1 "hot" doctor with 20 slots: the contention scenario sends 10
--     patients at each of them at once.
--   * 3 "cold" doctors with 1,100 slots each for the booking-throughput
--     scenario. Their grids start 7, 14 and 21 minutes past the hot grid,
--     so no two slots anywhere start at the same instant and one patient can
--     hold many of them without tripping uq_patient_active_at_time.
--   * 300 patients and 1 admin. Passwords are hashed by pgcrypto at BCrypt
--     cost 4 instead of the application's 12: login is not what this test
--     measures, and 300 logins at ~250 ms each would dominate setup.
--     Spring's BCrypt verifier reads the cost from the hash.
-- =====================================================================

\set password 'load-test-password'

INSERT INTO users (id, email, password_hash, full_name, role)
SELECT ('00000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
       'lt-doctor-' || n || '@medicity.test',
       crypt(:'password', gen_salt('bf', 4)),
       'Load Doctor ' || n,
       'DOCTOR'
FROM generate_series(1, 4) n;

INSERT INTO doctors (id, user_id, specialization, license_number, consultation_fee, years_experience)
SELECT ('d0000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
       ('00000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
       'Load Testing', 'LT-' || n, 500.00, 5
FROM generate_series(1, 4) n;

-- Hot: 20 consecutive slots for doctor 1, starting tomorrow.
INSERT INTO appointment_slots (doctor_id, starts_at, ends_at, status)
SELECT 'd0000000-0000-4000-8000-000000000001',
       date_trunc('hour', now()) + INTERVAL '1 day' + i * INTERVAL '30 minutes',
       date_trunc('hour', now()) + INTERVAL '1 day' + (i + 1) * INTERVAL '30 minutes',
       'OPEN'
FROM generate_series(0, 19) i;

-- Cold: doctors 2-4, ~23 days of back-to-back slots each, offset 7 minutes per doctor.
INSERT INTO appointment_slots (doctor_id, starts_at, ends_at, status)
SELECT ('d0000000-0000-4000-8000-' || lpad(d::text, 12, '0'))::uuid,
       date_trunc('hour', now()) + INTERVAL '1 day' + (d - 1) * INTERVAL '7 minutes' + i * INTERVAL '30 minutes',
       date_trunc('hour', now()) + INTERVAL '1 day' + (d - 1) * INTERVAL '7 minutes' + (i + 1) * INTERVAL '30 minutes',
       'OPEN'
FROM generate_series(2, 4) d, generate_series(0, 1099) i;

INSERT INTO users (id, email, password_hash, full_name, role)
SELECT ('10000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
       'lt-patient-' || n || '@medicity.test',
       crypt(:'password', gen_salt('bf', 4)),
       'Load Patient ' || n,
       'PATIENT'
FROM generate_series(1, 300) n;

INSERT INTO patients (user_id, date_of_birth, gender)
SELECT ('10000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid, DATE '1990-01-01', 'UNDISCLOSED'
FROM generate_series(1, 300) n;

INSERT INTO users (email, password_hash, full_name, role)
VALUES ('lt-admin@medicity.test', crypt(:'password', gen_salt('bf', 4)), 'Load Admin', 'ADMIN');
