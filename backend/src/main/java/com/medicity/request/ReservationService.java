package com.medicity.request;

import com.medicity.audit.AuditLog;
import com.medicity.common.ConflictException;
import com.medicity.common.Constraints;
import com.medicity.common.NotFoundException;
import com.medicity.common.ValidationException;
import com.medicity.outbox.Outbox;
import com.medicity.patient.Patient;
import com.medicity.patient.ActingPatient;
import com.medicity.request.Comparison.StoreAnswer;
import com.medicity.store.Store;
import com.medicity.store.StoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Reserving at one store and collecting with a code.
 *
 * <p>The six-digit code is shown only to the patient. At the counter the
 * chemist types in what the patient shows; a match is what marks the medicines
 * collected. The store never receives the code, so a store cannot mark a
 * reservation collected without the patient there, and someone who only knows
 * the patient's name cannot collect their medicines.
 *
 * <p>Six digits is guessable with enough tries, so tries are capped: five
 * wrong codes lock the reservation against codes (the patient can cancel and
 * reserve again). A wrong attempt is counted in the database and survives the
 * error response; see {@link #collect}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationService {

    static final int MAX_WRONG_CODES = 5;
    private static final String UQ_LIVE = "uq_reservation_live_per_request";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final ActingPatient acting;
    private final MedicineRequestService requests;
    private final StoreService storeService;
    private final AuditLog auditLog;
    private final Outbox outbox;
    private final Clock clock;

    // --- patient -----------------------------------------------------------------

    /**
     * Reserves what one store said it has. The question moves from OPEN to
     * RESERVED in one conditional UPDATE, so two taps on two stores' "Reserve"
     * buttons cannot both hold medicines: the second finds it no longer open.
     */
    @Transactional
    public UUID reserve(UUID patientUserId, UUID requestId, UUID storeId) {
        Patient patient = requirePatient(patientUserId);
        Comparison comparison = requests.compare(patientUserId, requestId);
        StoreAnswer answer = comparison.stores().stream().filter(s -> s.storeId().equals(storeId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Store on this question", storeId));
        if (!answer.answered()) {
            throw new ValidationException("NOT_ANSWERED", answer.name() + " has not answered yet");
        }
        if (answer.medicinesAvailable() == 0) {
            throw new ValidationException("NOTHING_TO_RESERVE", answer.name() + " has none of these medicines");
        }

        Instant now = clock.instant();
        int moved = jdbc.update("""
                UPDATE medicine_requests SET status = 'RESERVED'
                WHERE id = ? AND patient_id = ? AND status = 'OPEN' AND expires_at > ?
                """, requestId, patient.getId(), Timestamp.from(now));
        if (moved == 0) {
            throw new ConflictException("NOT_OPEN",
                    "This question is no longer open: it was reserved, closed or has expired");
        }

        int holdHours = jdbc.queryForObject("SELECT hold_hours FROM stores WHERE id = ?", Integer.class, storeId);
        Instant expiresAt = now.plus(Duration.ofHours(holdHours));
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO reservations (id, request_id, store_id, patient_id, pickup_code, total, complete,
                                              created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, requestId, storeId, patient.getId(), newCode(), answer.total(), answer.complete(),
                    Timestamp.from(now), Timestamp.from(expiresAt));
        } catch (DataIntegrityViolationException e) {
            if (Constraints.isViolationOf(e, UQ_LIVE)) {
                throw new ConflictException("ALREADY_RESERVED", "This question is already reserved at a store");
            }
            throw e;
        }

        UUID ownerUserId = jdbc.queryForObject("SELECT owner_user_id FROM stores WHERE id = ?", UUID.class, storeId);
        auditLog.recordChange("RESERVATION_MADE", "RESERVATION", id,
                Map.of("requestId", requestId, "storeId", storeId, "complete", answer.complete()));
        outbox.publish(Outbox.RESERVATION_MADE, id, Map.of(
                "storeOwnerUserId", ownerUserId,
                "patientName", patientName(patient),
                "medicines", answer.medicinesAvailable(),
                "expiresAt", expiresAt.toString()));
        log.info("Reservation {} at store {} until {}", id, storeId, expiresAt);
        return id;
    }

    /** The patient no longer needs it. The question reopens if it has not expired. */
    @Transactional
    public void cancel(UUID patientUserId, UUID reservationId) {
        Patient patient = requirePatient(patientUserId);
        Instant now = clock.instant();
        List<Ended> ended = jdbc.query("""
                UPDATE reservations SET status = 'CANCELLED', ended_at = ?
                WHERE id = ? AND patient_id = ? AND status = 'HELD'
                RETURNING request_id, store_id
                """, (rs, i) -> new Ended(reservationId, rs.getObject("request_id", UUID.class),
                rs.getObject("store_id", UUID.class)), Timestamp.from(now), reservationId, patient.getId());
        if (ended.isEmpty()) {
            String status = jdbc.query("SELECT status FROM reservations WHERE id = ? AND patient_id = ?",
                            (rs, i) -> rs.getString(1), reservationId, patient.getId()).stream().findFirst()
                    .orElseThrow(() -> new NotFoundException("Reservation", reservationId));
            throw new ConflictException("NOT_HELD", "This reservation is already " + status.toLowerCase());
        }
        Ended e = ended.get(0);
        reopen(e.requestId(), now);
        auditLog.recordChange("RESERVATION_CANCELLED", "RESERVATION", reservationId, null);
        outbox.publish(Outbox.RESERVATION_CANCELLED, reservationId, Map.of(
                "storeOwnerUserId", ownerOf(e.storeId()), "patientName", patientName(patient)));
    }

    // --- store -----------------------------------------------------------------------

    /**
     * Hands over the medicines, if the code the patient shows matches.
     *
     * <p>{@code noRollbackFor}: a wrong code is counted, then refused. The
     * count must survive the refusal, or every guess would be free.
     */
    @Transactional(noRollbackFor = WrongPickupCodeException.class)
    public void collect(UUID ownerUserId, UUID reservationId, String code) {
        Store store = storeService.requireOwn(ownerUserId);
        Instant now = clock.instant();
        List<UUID> collected = jdbc.queryForList("""
                UPDATE reservations SET status = 'COLLECTED', collected_at = ?, ended_at = ?
                WHERE id = ? AND store_id = ? AND status = 'HELD' AND expires_at > ?
                  AND wrong_code_attempts < ? AND pickup_code = ?
                RETURNING request_id
                """, UUID.class, Timestamp.from(now), Timestamp.from(now), reservationId, store.getId(),
                Timestamp.from(now), MAX_WRONG_CODES, code);

        if (!collected.isEmpty()) {
            UUID requestId = collected.get(0);
            jdbc.update("UPDATE medicine_requests SET status = 'CLOSED', closed_at = ? WHERE id = ?",
                    Timestamp.from(now), requestId);
            UUID patientUserId = jdbc.queryForObject("""
                    SELECT coalesce(p.user_id, p.guardian_user_id) FROM reservations r JOIN patients p ON p.id = r.patient_id WHERE r.id = ?
                    """, UUID.class, reservationId);
            auditLog.recordChange("RESERVATION_COLLECTED", "RESERVATION", reservationId,
                    Map.of("storeId", store.getId()));
            outbox.publish(Outbox.RESERVATION_COLLECTED, reservationId, Map.of(
                    "patientUserId", patientUserId, "storeName", store.getName()));
            return;
        }

        // Not collected. Say why, in the order a chemist can act on.
        Held held = jdbc.query("""
                SELECT status, expires_at, wrong_code_attempts FROM reservations WHERE id = ? AND store_id = ?
                """, (rs, i) -> new Held(rs.getString("status"), rs.getTimestamp("expires_at").toInstant(),
                rs.getInt("wrong_code_attempts")), reservationId, store.getId()).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Reservation", reservationId));
        if (!"HELD".equals(held.status())) {
            throw new ConflictException("NOT_HELD", "This reservation is " + held.status().toLowerCase());
        }
        if (!held.expiresAt().isAfter(now)) {
            throw new ConflictException("RESERVATION_EXPIRED", "This reservation has expired");
        }
        if (held.wrongAttempts() >= MAX_WRONG_CODES) {
            throw new ConflictException("CODE_LOCKED",
                    "Too many wrong codes. The patient can cancel and reserve again from the app.");
        }
        int attempts = jdbc.queryForObject("""
                UPDATE reservations SET wrong_code_attempts = wrong_code_attempts + 1
                WHERE id = ? RETURNING wrong_code_attempts
                """, Integer.class, reservationId);
        auditLog.recordChange("PICKUP_CODE_WRONG", "RESERVATION", reservationId,
                Map.of("storeId", store.getId(), "attempts", attempts));
        throw new WrongPickupCodeException(MAX_WRONG_CODES - attempts);
    }

    /** What the store must keep aside, soonest expiry first; or what it handed over. */
    @Transactional(readOnly = true)
    public List<StoreReservation> forStore(UUID ownerUserId, boolean held) {
        Store store = storeService.requireOwn(ownerUserId);
        List<StoreReservation> rows = jdbc.query("""
                SELECT r.id, r.request_id, r.status, r.total, r.complete, r.created_at, r.expires_at,
                       r.collected_at, r.wrong_code_attempts, coalesce(pu.full_name, p.full_name) AS patient_name
                FROM reservations r
                JOIN patients p ON p.id = r.patient_id
                LEFT JOIN users pu ON pu.id = p.user_id
                WHERE r.store_id = ? AND %s
                ORDER BY %s
                LIMIT 50
                """.formatted(held ? "r.status = 'HELD'" : "r.status <> 'HELD'",
                        held ? "r.expires_at" : "r.ended_at DESC NULLS LAST"),
                (rs, i) -> new StoreReservation(rs.getObject("id", UUID.class),
                        rs.getObject("request_id", UUID.class), rs.getString("status"),
                        // The name the store checks at the counter, now that the patient chose it.
                        rs.getString("patient_name"), rs.getBigDecimal("total"), rs.getBoolean("complete"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                        ts(rs.getTimestamp("collected_at")), rs.getInt("wrong_code_attempts") >= MAX_WRONG_CODES,
                        List.of()),
                store.getId());
        return rows.stream().map(r -> r.withLines(linesToHandOver(r.requestId(), store.getId()))).toList();
    }

    // --- shared ----------------------------------------------------------------------

    /** The patient's latest reservation on a question, with the code; null if none. */
    @Transactional(readOnly = true)
    public PatientReservation latestFor(UUID requestId) {
        return jdbc.query("""
                SELECT r.id, r.store_id, s.name, s.phone, s.address_line, r.pickup_code, r.status, r.total,
                       r.complete, r.expires_at, r.collected_at
                FROM reservations r JOIN stores s ON s.id = r.store_id
                WHERE r.request_id = ?
                ORDER BY r.created_at DESC
                LIMIT 1
                """, (rs, i) -> {
                    String status = rs.getString("status");
                    return new PatientReservation(rs.getObject("id", UUID.class), rs.getObject("store_id", UUID.class),
                            rs.getString("name"), rs.getString("phone"), rs.getString("address_line"),
                            // Once it is over, the code has no use; do not keep showing it.
                            "HELD".equals(status) ? rs.getString("pickup_code") : null,
                            status, rs.getBigDecimal("total"), rs.getBoolean("complete"),
                            rs.getTimestamp("expires_at").toInstant(), ts(rs.getTimestamp("collected_at")));
                }, requestId).stream().findFirst().orElse(null);
    }

    /** When and where each prescription was last collected through a store. */
    @Transactional(readOnly = true)
    public Map<UUID, Collected> lastCollected(Collection<UUID> prescriptionIds) {
        if (prescriptionIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Collected> result = new HashMap<>();
        jdbc.query("""
                SELECT DISTINCT ON (q.prescription_id) q.prescription_id, r.collected_at, s.name
                FROM reservations r
                JOIN medicine_requests q ON q.id = r.request_id
                JOIN stores s ON s.id = r.store_id
                WHERE r.status = 'COLLECTED' AND q.prescription_id = ANY (?)
                ORDER BY q.prescription_id, r.collected_at DESC
                """, ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", prescriptionIds.toArray())),
                rs -> {
                    result.put(rs.getObject("prescription_id", UUID.class),
                            new Collected(rs.getTimestamp("collected_at").toInstant(), rs.getString("name")));
                });
        return result;
    }

    /** Held reservations past their time go back to the shelf; their questions reopen or expire. */
    @Transactional
    public int expireOverdue() {
        Instant now = clock.instant();
        List<Ended> expired = jdbc.query("""
                UPDATE reservations SET status = 'EXPIRED', ended_at = ?
                WHERE status = 'HELD' AND expires_at <= ?
                RETURNING id, request_id, store_id
                """, (rs, i) -> new Ended(rs.getObject("id", UUID.class), rs.getObject("request_id", UUID.class),
                rs.getObject("store_id", UUID.class)), Timestamp.from(now), Timestamp.from(now));
        for (Ended e : expired) {
            reopen(e.requestId(), now);
            Map<String, Object> who = jdbc.queryForMap("""
                    SELECT coalesce(p.user_id, p.guardian_user_id) AS patient_user_id, coalesce(pu.full_name, p.full_name) AS patient_name,
                           s.owner_user_id, s.name
                    FROM reservations r
                    JOIN patients p ON p.id = r.patient_id LEFT JOIN users pu ON pu.id = p.user_id
                    JOIN stores s ON s.id = r.store_id
                    WHERE r.id = ?
                    """, e.reservationId());
            auditLog.recordChange("RESERVATION_EXPIRED", "RESERVATION", e.reservationId(), null);
            outbox.publish(Outbox.RESERVATION_EXPIRED, e.reservationId(), Map.of(
                    "patientUserId", who.get("patient_user_id"),
                    "patientName", MedicineRequestService.shortName((String) who.get("patient_name")),
                    "storeOwnerUserId", who.get("owner_user_id"),
                    "storeName", who.get("name")));
        }
        return expired.size();
    }

    /** A question whose reservation ended is open again, unless its own time is up. */
    private void reopen(UUID requestId, Instant now) {
        jdbc.update("""
                UPDATE medicine_requests
                SET status = CASE WHEN expires_at > ? THEN 'OPEN' ELSE 'EXPIRED' END,
                    closed_at = CASE WHEN expires_at > ? THEN NULL ELSE CAST(? AS timestamptz) END
                WHERE id = ? AND status = 'RESERVED'
                """, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), requestId);
    }

    private List<HandOver> linesToHandOver(UUID requestId, UUID storeId) {
        return jdbc.query("""
                SELECT m.name, m.strength, coalesce(sm.name, m.name) AS give_name,
                       coalesce(sm.strength, m.strength) AS give_strength,
                       l.availability, l.quantity_available, i.quantity AS asked, l.unit_price
                FROM request_answer_lines l
                JOIN medicine_request_items i ON i.request_id = l.request_id AND i.medicine_id = l.medicine_id
                JOIN medicines m ON m.id = l.medicine_id
                LEFT JOIN medicines sm ON sm.id = l.substitute_medicine_id
                WHERE l.request_id = ? AND l.store_id = ? AND l.availability <> 'NO'
                ORDER BY m.name
                """, (rs, i) -> new HandOver(rs.getString("give_name"), rs.getString("give_strength"),
                rs.getString("name"), rs.getInt("quantity_available"), rs.getInt("asked"),
                rs.getBigDecimal("unit_price")), requestId, storeId);
    }

    private UUID ownerOf(UUID storeId) {
        return jdbc.queryForObject("SELECT owner_user_id FROM stores WHERE id = ?", UUID.class, storeId);
    }

    private Patient requirePatient(UUID userId) {
        return acting.resolve(userId);
    }

    private static String patientName(Patient p) {
        return p.displayName();
    }

    private static String newCode() {
        return "%06d".formatted(RANDOM.nextInt(1_000_000));
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private record Ended(UUID reservationId, UUID requestId, UUID storeId) {}

    private record Held(String status, Instant expiresAt, int wrongAttempts) {}

    public record Collected(Instant at, String storeName) {}

    public record HandOver(String name, String strength, String prescribedAs, int quantity, int asked,
                           BigDecimal unitPrice) {}

    public record StoreReservation(UUID id, UUID requestId, String status, String patientName, BigDecimal total,
                                   boolean complete, Instant createdAt, Instant expiresAt, Instant collectedAt,
                                   boolean codeLocked, List<HandOver> lines) {
        StoreReservation withLines(List<HandOver> l) {
            return new StoreReservation(id, requestId, status, patientName, total, complete, createdAt, expiresAt,
                    collectedAt, codeLocked, l);
        }
    }

    public record PatientReservation(UUID id, UUID storeId, String storeName, String storePhone, String storeAddress,
                                     /** Shown to the patient only, and only while held. */
                                     String pickupCode,
                                     String status, BigDecimal total, boolean complete, Instant expiresAt,
                                     Instant collectedAt) {}
}
