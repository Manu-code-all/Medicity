package com.medicity.perf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicity.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hot queries, planned against a database the size of a busy clinic
 * network rather than the demo's few dozen rows.
 *
 * <p>On a small table PostgreSQL rightly reads the whole thing, so a plan
 * checked against demo data proves nothing. This seeds 200 doctors, 120,000
 * slots, 60,000 visits, 10,000 patients, 100,000 notifications and 50,000
 * outbox events, runs {@code ANALYZE}, then {@code EXPLAIN ANALYZE}s each
 * query the way the application sends it. A sequential scan of one of the
 * large tables fails the test: it means a query that is fast today will slow
 * down in proportion to the data.
 *
 * <p>Everything happens in one transaction that is rolled back, statistics
 * included, so no other test sees the rows. The plans and timings are written
 * to {@code target/query-plans.md} and, in CI, to the job summary.
 */
@DisplayName("Query plans at scale")
class QueryPlanTest extends AbstractIntegrationTest {

    /** Tables that grow with use. Reading one of these whole is the regression. */
    private static final Set<String> LARGE = Set.of(
            "appointments", "appointment_slots", "patients", "users", "notifications", "outbox_events");

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("no hot query reads a large table whole")
    void hotQueriesUseIndexes() throws IOException {
        List<Result> results = new ArrayList<>();
        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            seed();
            UUID doctor = jdbc.queryForObject("SELECT id FROM qp_doctor WHERE n = 7", UUID.class);
            UUID patient = jdbc.queryForObject("SELECT id FROM qp_patient WHERE n = 42", UUID.class);
            UUID patientUser = jdbc.queryForObject("SELECT user_id FROM qp_patient WHERE n = 42", UUID.class);
            String page = jdbc.queryForList("SELECT id FROM qp_doctor WHERE n <= 20", UUID.class).stream()
                    .map(id -> "'" + id + "'").reduce((a, b) -> a + "," + b).orElseThrow();
            queries(doctor, patient, patientUser, page).forEach((name, sql) -> results.add(explain(name, sql)));
        });

        report(results);
        assertThat(results).allSatisfy(r -> assertThat(r.fullScans())
                .as("%s reads a large table whole:%n%s", r.name(), r.plan())
                .isEmpty());
    }

    /** As the application sends them; JPQL queries written out as the SQL Hibernate generates. */
    private static Map<String, String> queries(UUID doctor, UUID patient, UUID patientUser, String page) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("Directory: next 3 free slots for a page of 20 doctors", """
                SELECT free.id FROM doctors d
                CROSS JOIN LATERAL (
                    SELECT s.id FROM appointment_slots s
                    WHERE s.doctor_id = d.id AND s.status = 'OPEN'
                      AND s.starts_at >= now() AND s.starts_at < now() + interval '14 days'
                      AND NOT EXISTS (SELECT 1 FROM appointments a WHERE a.slot_id = s.id AND a.status <> 'CANCELLED')
                    ORDER BY s.starts_at LIMIT 3
                ) free
                WHERE d.id IN (%s) AND d.verified_at IS NOT NULL
                """.formatted(page));
        q.put("Booking page: a doctor's free slots for a day", """
                SELECT s.* FROM appointment_slots s JOIN doctors d ON d.id = s.doctor_id
                WHERE s.doctor_id = '%s' AND d.verified_at IS NOT NULL AND s.status = 'OPEN'
                  AND s.starts_at >= now() AND s.starts_at < now() + interval '1 day'
                  AND NOT EXISTS (SELECT 1 FROM appointments a WHERE a.slot_id = s.id AND a.status <> 'CANCELLED')
                ORDER BY s.starts_at
                """.formatted(doctor));
        q.put("Patient: upcoming visits", """
                SELECT a.*, s.*, d.*, u.* FROM appointments a
                JOIN appointment_slots s ON s.id = a.slot_id JOIN doctors d ON d.id = s.doctor_id
                JOIN users u ON u.id = d.user_id
                WHERE a.patient_id = '%s' AND a.status = 'BOOKED' AND a.scheduled_at >= now()
                ORDER BY a.scheduled_at LIMIT 20
                """.formatted(patient));
        q.put("Patient: past visits", """
                SELECT a.*, s.*, d.*, u.* FROM appointments a
                JOIN appointment_slots s ON s.id = a.slot_id JOIN doctors d ON d.id = s.doctor_id
                JOIN users u ON u.id = d.user_id
                WHERE a.patient_id = '%s' AND (a.status <> 'BOOKED' OR a.scheduled_at < now())
                ORDER BY a.scheduled_at DESC LIMIT 20
                """.formatted(patient));
        q.put("Doctor: the day's visits", """
                SELECT a.*, s.*, p.*, pu.* FROM appointments a
                JOIN appointment_slots s ON s.id = a.slot_id JOIN patients p ON p.id = a.patient_id
                LEFT JOIN users pu ON pu.id = p.user_id
                WHERE s.doctor_id = '%s' AND s.starts_at >= date_trunc('day', now()) AND s.starts_at < date_trunc('day', now()) + interval '1 day'
                ORDER BY s.starts_at
                """.formatted(doctor));
        q.put("Doctor: visits, newest first (a page)", """
                SELECT a.*, s.*, p.*, pu.* FROM appointments a
                JOIN appointment_slots s ON s.id = a.slot_id JOIN patients p ON p.id = a.patient_id
                LEFT JOIN users pu ON pu.id = p.user_id
                WHERE s.doctor_id = '%s'
                ORDER BY s.starts_at DESC LIMIT 20
                """.formatted(doctor));
        q.put("Is the doctor running late?", """
                SELECT min(s.ends_at) FILTER (WHERE s.ends_at <= now()), count(*) > 0
                FROM appointments a JOIN appointment_slots s ON s.id = a.slot_id
                WHERE s.doctor_id = '%s' AND a.status = 'BOOKED'
                  AND s.starts_at <= now() AND s.ends_at > now() - interval '90 minutes'
                """.formatted(doctor));
        q.put("Hourly job: visits never closed", """
                SELECT a.id FROM appointments a JOIN appointment_slots s ON s.id = a.slot_id
                WHERE a.status = 'BOOKED' AND a.scheduled_at < now() - interval '12 hours'
                  AND s.ends_at < now() - interval '12 hours'
                """);
        q.put("Bell: latest notifications", """
                SELECT * FROM notifications WHERE user_id = '%s' ORDER BY created_at DESC LIMIT 20
                """.formatted(patientUser));
        q.put("Bell: unread count", """
                SELECT count(*) FROM notifications WHERE user_id = '%s' AND read_at IS NULL
                """.formatted(patientUser));
        q.put("Outbox relay: next due event", """
                SELECT id, attempts FROM outbox_events
                WHERE published_at IS NULL AND failed_at IS NULL AND next_attempt_at <= now()
                ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                """);
        return q;
    }

    // --- seeding ------------------------------------------------------------------

    private void seed() {
        jdbc.execute("""
                CREATE TEMP TABLE qp_doctor ON COMMIT DROP AS
                SELECT g AS n, gen_random_uuid() AS id, gen_random_uuid() AS user_id FROM generate_series(1, 200) g;
                CREATE TEMP TABLE qp_patient ON COMMIT DROP AS
                SELECT g AS n, gen_random_uuid() AS id, gen_random_uuid() AS user_id FROM generate_series(0, 9972) g;

                INSERT INTO users (id, email, password_hash, full_name, role)
                SELECT user_id, 'qp-doctor-' || n || '@plan.test', 'x', 'Dr. Plan ' || n, 'DOCTOR' FROM qp_doctor;
                INSERT INTO doctors (id, user_id, specialization, license_number, consultation_fee)
                SELECT id, user_id, (ARRAY['Cardiology','Dermatology','Paediatrics','Orthopaedics','General Medicine'])[1 + n % 5],
                       'QP-' || n, 500 FROM qp_doctor;
                INSERT INTO users (id, email, password_hash, full_name, role)
                SELECT user_id, 'qp-patient-' || n || '@plan.test', 'x', 'Patient ' || n, 'PATIENT' FROM qp_patient;
                INSERT INTO patients (id, user_id, date_of_birth, gender)
                SELECT id, user_id, DATE '1990-01-01', 'FEMALE' FROM qp_patient;

                -- 600 half-hour slots a doctor, half in the past and half ahead.
                CREATE TEMP TABLE qp_slot ON COMMIT DROP AS
                SELECT d.n AS doctor_n, i, gen_random_uuid() AS id, d.id AS doctor_id,
                       date_trunc('hour', now()) + (i - 300) * interval '30 minutes' AS starts_at
                FROM qp_doctor d, generate_series(0, 599) i;
                INSERT INTO appointment_slots (id, doctor_id, starts_at, ends_at)
                SELECT id, doctor_id, starts_at, starts_at + interval '30 minutes' FROM qp_slot;

                -- Every other slot booked. 9973 is prime, so no patient is booked
                -- with two doctors at the same moment.
                INSERT INTO appointments (slot_id, patient_id, status, scheduled_at)
                SELECT s.id, p.id,
                       CASE WHEN s.starts_at >= now() THEN 'BOOKED'
                            WHEN s.i % 50 = 0 THEN 'BOOKED'
                            WHEN s.i % 10 = 0 THEN 'NO_SHOW'
                            ELSE 'COMPLETED' END,
                       s.starts_at
                FROM qp_slot s JOIN qp_patient p ON p.n = (s.doctor_n * 600 + s.i) % 9973
                WHERE s.i % 2 = 0;

                INSERT INTO notifications (user_id, event_id, kind, title, body, created_at, read_at)
                SELECT p.user_id, gen_random_uuid(), 'PLAN', 'Title', 'Body',
                       now() - g * interval '1 minute', CASE WHEN g % 5 = 0 THEN NULL ELSE now() END
                FROM generate_series(1, 100000) g JOIN qp_patient p ON p.n = g % 9973;

                INSERT INTO outbox_events (event_type, aggregate_id, payload, published_at)
                SELECT 'PLAN', gen_random_uuid(), '{}'::jsonb, now() FROM generate_series(1, 50000);
                INSERT INTO outbox_events (event_type, aggregate_id, payload)
                SELECT 'PLAN', gen_random_uuid(), '{}'::jsonb FROM generate_series(1, 20);

                ANALYZE users; ANALYZE doctors; ANALYZE patients; ANALYZE appointment_slots;
                ANALYZE appointments; ANALYZE notifications; ANALYZE outbox_events;
                """);
    }

    // --- planning -----------------------------------------------------------------

    private Result explain(String name, String sql) {
        String raw = jdbc.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql, String.class);
        String text = String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class));
        try {
            JsonNode root = json.readTree(raw).get(0);
            List<String> fullScans = new ArrayList<>();
            walk(root.get("Plan"), fullScans);
            return new Result(name, root.get("Execution Time").asDouble(), fullScans, text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void walk(JsonNode node, List<String> fullScans) {
        String relation = node.path("Relation Name").asText("");
        if ("Seq Scan".equals(node.path("Node Type").asText()) && LARGE.contains(relation)) {
            fullScans.add(relation);
        }
        node.path("Plans").forEach(child -> walk(child, fullScans));
    }

    private static void report(List<Result> results) throws IOException {
        StringBuilder md = new StringBuilder("### Query plans at scale\n\n")
                .append("200 doctors, 120,000 slots, 60,000 visits, 10,000 patients, 100,000 notifications.\n\n")
                .append("| Query | Execution | Reads a large table whole |\n|---|---:|---|\n");
        for (Result r : results) {
            md.append("| %s | %.2f ms | %s |\n".formatted(r.name(), r.millis(),
                    r.fullScans().isEmpty() ? "no" : "**" + String.join(", ", r.fullScans()) + "**"));
        }
        md.append("\n<details><summary>Plans</summary>\n\n");
        for (Result r : results) {
            md.append("**").append(r.name()).append("**\n\n```\n").append(r.plan()).append("\n```\n\n");
        }
        md.append("</details>\n");
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "query-plans.md"), md);
        String summary = System.getenv("GITHUB_STEP_SUMMARY");
        if (summary != null && !summary.isBlank()) {
            Files.writeString(Path.of(summary), md, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        System.out.println(md);
    }

    private record Result(String name, double millis, List<String> fullScans, String plan) {}
}
