package com.medicity.request;

import com.medicity.store.Store;
import com.medicity.store.StoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * "What are people near me asking for, and what did I say?" For a store
 * deciding what to stock.
 *
 * <p>Built only from questions that were sent to this store, so a store learns
 * nothing about questions it was never part of, and only as counts per
 * medicine: no patient, prescription or doctor appears. A medicine asked
 * about by fewer than {@value #MIN_PATIENTS} different patients is left out,
 * so a rare medicine cannot point at the one person nearby who takes it.
 */
@Service
@RequiredArgsConstructor
public class StoreInsightsService {

    static final Duration WINDOW = Duration.ofDays(7);
    static final int MIN_PATIENTS = 2;
    /** Said "no" or "partly" this many times: worth considering stocking. */
    static final int STOCK_SUGGESTION_AT = 2;

    private final JdbcTemplate jdbc;
    private final StoreService storeService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Insights forStore(UUID ownerUserId) {
        Store store = storeService.requireOwn(ownerUserId);
        Timestamp since = Timestamp.from(clock.instant().minus(WINDOW));

        Summary summary = jdbc.queryForObject("""
                SELECT count(*) AS received,
                       count(*) FILTER (WHERE rr.status = 'ANSWERED') AS answered,
                       count(*) FILTER (WHERE rr.answered_automatically) AS automatic,
                       (SELECT count(*) FROM reservations res
                         WHERE res.store_id = ? AND res.created_at >= ?) AS reserved,
                       (SELECT count(*) FROM reservations res
                         WHERE res.store_id = ? AND res.created_at >= ? AND res.status = 'COLLECTED') AS collected,
                       percentile_cont(0.5) WITHIN GROUP (
                           ORDER BY extract(epoch FROM rr.answered_at - r.created_at))
                           FILTER (WHERE rr.status = 'ANSWERED' AND NOT rr.answered_automatically)
                           AS median_answer_seconds
                FROM request_recipients rr JOIN medicine_requests r ON r.id = rr.request_id
                WHERE rr.store_id = ? AND r.created_at >= ?
                """, (rs, i) -> new Summary(rs.getInt("received"), rs.getInt("answered"), rs.getInt("automatic"),
                rs.getInt("reserved"), rs.getInt("collected"),
                rs.getObject("median_answer_seconds") == null ? null
                        : (int) Math.round(rs.getDouble("median_answer_seconds") / 60.0)),
                store.getId(), since, store.getId(), since, store.getId(), since);

        List<MedicineDemand> medicines = jdbc.query("""
                SELECT m.id, m.name, m.strength, m.form,
                       count(*) AS asked,
                       count(DISTINCT r.patient_id) AS patients,
                       sum(i.quantity) AS units,
                       count(*) FILTER (WHERE l.availability = 'YES') AS had,
                       count(*) FILTER (WHERE l.availability = 'PARTIAL') AS partly,
                       count(*) FILTER (WHERE l.availability = 'NO') AS said_no,
                       count(*) FILTER (WHERE l.availability IS NULL) AS unanswered,
                       count(*) FILTER (WHERE res.store_id IS NOT NULL AND res.store_id <> rr.store_id)
                           AS went_elsewhere
                FROM request_recipients rr
                JOIN medicine_requests r ON r.id = rr.request_id
                JOIN medicine_request_items i ON i.request_id = r.id
                JOIN medicines m ON m.id = i.medicine_id
                LEFT JOIN request_answer_lines l
                       ON l.request_id = rr.request_id AND l.store_id = rr.store_id AND l.medicine_id = i.medicine_id
                LEFT JOIN reservations res
                       ON res.request_id = r.id AND res.status IN ('HELD', 'COLLECTED')
                WHERE rr.store_id = ? AND r.created_at >= ?
                GROUP BY m.id, m.name, m.strength, m.form
                HAVING count(DISTINCT r.patient_id) >= ?
                ORDER BY count(*) FILTER (WHERE l.availability IN ('NO', 'PARTIAL')) DESC, count(*) DESC, m.name
                LIMIT 25
                """, (rs, i) -> {
                    int said = rs.getInt("said_no") + rs.getInt("partly");
                    return new MedicineDemand(rs.getObject("id", UUID.class), rs.getString("name"),
                            rs.getString("strength"), rs.getString("form"), rs.getInt("asked"),
                            rs.getInt("patients"), rs.getInt("units"), rs.getInt("had"), rs.getInt("partly"),
                            rs.getInt("said_no"), rs.getInt("unanswered"), rs.getInt("went_elsewhere"),
                            said >= STOCK_SUGGESTION_AT);
                }, store.getId(), since, MIN_PATIENTS);

        return new Insights(since.toInstant(), clock.instant(), summary, medicines);
    }

    public record Summary(int questionsReceived, int answered, int answeredAutomatically, int reservations,
                          int collected, Integer medianMinutesToAnswer) {}

    public record MedicineDemand(UUID medicineId, String name, String strength, String form,
                                 /** Questions to this store that included this medicine. */
                                 int asked,
                                 int patients,
                                 int units,
                                 int had,
                                 int partly,
                                 int saidNo,
                                 int unanswered,
                                 /** Reserved at another store instead. */
                                 int wentElsewhere,
                                 /** Often asked for and not fully in stock: consider stocking it. */
                                 boolean considerStocking) {}

    public record Insights(Instant from, Instant to, Summary summary, List<MedicineDemand> medicines) {}
}
