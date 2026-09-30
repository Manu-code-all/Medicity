package com.medicity.request;

import com.medicity.audit.AuditLog;
import com.medicity.clinical.Prescription;
import com.medicity.clinical.PrescriptionItem;
import com.medicity.clinical.PrescriptionRepository;
import com.medicity.common.ConflictException;
import com.medicity.common.Constraints;
import com.medicity.common.NotFoundException;
import com.medicity.common.ValidationException;
import com.medicity.outbox.Outbox;
import com.medicity.patient.Patient;
import com.medicity.patient.ActingPatient;
import com.medicity.store.Store;
import com.medicity.store.StoreDirectory;
import com.medicity.store.StoreDirectory.StoreInReach;
import com.medicity.store.StoreService;
import com.medicity.store.StoreStockService;
import com.medicity.request.Comparison.StoreAnswer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Asking every nearby chemist at once, and their answers.
 *
 * <p>The question is a prescription the doctor issued in Medicity: the patient
 * cannot type in medicines, so a chemist never receives a list that no doctor
 * wrote. Stores see the prescribing doctor and their registration, the
 * medicines, and the patient's first name, not the diagnosis: what a chemist
 * needs to answer, and no more.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MedicineRequestService {

    /** How long a question stays open. Stock answers go stale after that. */
    static final Duration LIFETIME = Duration.ofHours(6);
    /** A question goes to at most this many stores, nearest first. */
    static final int MAX_STORES = 25;
    /** Open questions one patient may have at once. */
    static final int MAX_OPEN_PER_PATIENT = 5;
    private static final BigDecimal MAX_UNIT_PRICE = new BigDecimal("100000");
    private static final String UQ_ACTIVE = "uq_request_active_per_prescription";

    private final JdbcTemplate jdbc;
    private final ActingPatient acting;
    private final PrescriptionRepository prescriptionRepository;
    private final StoreDirectory storeDirectory;
    private final StoreService storeService;
    private final StoreStockService stockService;
    private final AuditLog auditLog;
    private final Outbox outbox;
    private final Clock clock;

    // --- the patient's side ------------------------------------------------

    /**
     * Sends one of the patient's current prescriptions to every verified store
     * within the radius.
     *
     * @param medicineIds which medicines to ask about; null or empty for all
     */
    @Transactional
    public UUID ask(UUID patientUserId, UUID prescriptionId, double lat, double lng, int radiusM,
                    Collection<UUID> medicineIds) {
        Patient patient = requirePatient(patientUserId);
        Prescription rx = prescriptionRepository.findWithItems(prescriptionId)
                // Someone else's prescription answers exactly like one that does not
                // exist: a 403 would confirm the id is real.
                .filter(p -> p.getPatient().getId().equals(patient.getId()))
                .orElseThrow(() -> new NotFoundException("Prescription", prescriptionId));
        if (prescriptionRepository.existsBySupersedesId(prescriptionId)) {
            throw new ValidationException("PRESCRIPTION_SUPERSEDED",
                    "Your doctor corrected this prescription. Ask about the corrected one instead.");
        }
        List<PrescriptionItem> items = choose(rx, medicineIds);

        StoreDirectory.Area area = StoreDirectory.Area.around(lat, lng, radiusM);
        Instant now = clock.instant();

        // A question left open past its lifetime still holds the "one open
        // question per prescription" slot until the expiry job runs. Expire it
        // here, so asking again right after it lapses is not refused.
        jdbc.update("""
                UPDATE medicine_requests SET status = 'EXPIRED', closed_at = ?
                WHERE prescription_id = ? AND status = 'OPEN' AND expires_at <= ?
                """, Timestamp.from(now), prescriptionId, Timestamp.from(now));

        // Lock the patient, so two questions sent at once cannot both pass a
        // count of four and make six.
        jdbc.query("SELECT id FROM patients WHERE id = ? FOR UPDATE", rs -> {}, patient.getId());
        Integer open = jdbc.queryForObject("""
                SELECT count(*) FROM medicine_requests
                WHERE patient_id = ? AND status = 'OPEN' AND expires_at > ?
                """, Integer.class, patient.getId(), Timestamp.from(now));
        if (open != null && open >= MAX_OPEN_PER_PATIENT) {
            // Every question lands in a dozen stores' queues. The cap keeps one
            // account from flooding a neighbourhood.
            throw new ConflictException("TOO_MANY_OPEN_QUESTIONS",
                    "You have %d open questions. Close one before asking again.".formatted(open));
        }

        List<StoreInReach> stores = storeDirectory.inReach(area, MAX_STORES);
        if (stores.isEmpty()) {
            throw new ValidationException("NO_STORES_NEARBY",
                    "No verified chemist within %s. Try a wider radius.".formatted(formatRadius(radiusM)));
        }

        UUID requestId = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO medicine_requests (id, patient_id, prescription_id, latitude, longitude, radius_m,
                                                   stores_asked, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, requestId, patient.getId(), prescriptionId, lat, lng, radiusM, stores.size(),
                    Timestamp.from(now), Timestamp.from(now.plus(LIFETIME)));
        } catch (DataIntegrityViolationException e) {
            if (Constraints.isViolationOf(e, UQ_ACTIVE)) {
                throw new ConflictException("ALREADY_ASKED",
                        "You already asked the chemists about this prescription. Close that question first.");
            }
            throw e;
        }
        jdbc.batchUpdate("""
                INSERT INTO medicine_request_items (request_id, medicine_id, quantity, substitution_allowed)
                VALUES (?, ?, ?, ?)
                """, items.stream().map(i -> new Object[]{requestId, i.getMedicine().getId(), i.getQuantity(),
                i.isSubstitutionAllowed()}).toList());
        jdbc.batchUpdate("INSERT INTO request_recipients (request_id, store_id, distance_m) VALUES (?, ?, ?)",
                stores.stream().map(s -> new Object[]{requestId, s.storeId(), s.distanceM()}).toList());

        auditLog.recordChange("MEDICINE_REQUEST_CREATED", "MEDICINE_REQUEST", requestId,
                Map.of("prescriptionId", prescriptionId, "storesAsked", stores.size(), "medicines", items.size()));

        // Stores that keep live stock and asked for it answer at once. The rest
        // answer by hand from their queue.
        List<Item> askedItems = items(requestId);
        for (StoreInReach store : stores) {
            stockService.answeringStock(store.storeId()).ifPresent(stock -> answerAs(store.storeId(),
                    storeName(store.storeId()), requestId, "Answered automatically from the store's live stock.",
                    fromStock(askedItems, stock), true));
        }
        outbox.publish(Outbox.MEDICINE_REQUEST_CREATED, requestId, Map.of(
                "storeOwnerUserIds", stores.stream().map(StoreInReach::ownerUserId).toList(),
                "medicines", items.size(),
                "doctorName", rx.getDoctor().getUser().getFullName()));
        log.info("Medicine request {} sent to {} stores", requestId, stores.size());
        return requestId;
    }

    /** The patient withdraws a question; stores stop seeing it. */
    @Transactional
    public void close(UUID patientUserId, UUID requestId) {
        Patient patient = requirePatient(patientUserId);
        int closed = jdbc.update("""
                UPDATE medicine_requests SET status = 'CLOSED', closed_at = ?
                WHERE id = ? AND patient_id = ? AND status = 'OPEN'
                """, Timestamp.from(clock.instant()), requestId, patient.getId());
        if (closed == 0) {
            String status = statusOfOwn(requestId, patient.getId());
            throw new ConflictException("NOT_OPEN", "This question is already " + status.toLowerCase() + ".");
        }
        auditLog.recordChange("MEDICINE_REQUEST_CLOSED", "MEDICINE_REQUEST", requestId, null);
    }

    @Transactional(readOnly = true)
    public List<RequestSummary> mine(UUID patientUserId) {
        Patient patient = requirePatient(patientUserId);
        return jdbc.query("""
                SELECT r.id, r.status, r.created_at, r.expires_at, r.stores_asked, r.prescription_id,
                       rx.diagnosis, du.full_name AS doctor_name,
                       (SELECT count(*) FROM request_recipients rr
                         WHERE rr.request_id = r.id AND rr.status = 'ANSWERED') AS answers,
                       (SELECT count(*) FROM medicine_request_items i WHERE i.request_id = r.id) AS medicines
                FROM medicine_requests r
                JOIN prescriptions rx ON rx.id = r.prescription_id
                JOIN doctors d ON d.id = rx.doctor_id
                JOIN users du ON du.id = d.user_id
                WHERE r.patient_id = ?
                ORDER BY r.created_at DESC
                LIMIT 30
                """, (rs, i) -> new RequestSummary(
                        rs.getObject("id", UUID.class),
                        effectiveStatus(rs.getString("status"), rs.getTimestamp("expires_at").toInstant()),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                        rs.getObject("prescription_id", UUID.class), rs.getString("diagnosis"),
                        rs.getString("doctor_name"), rs.getInt("medicines"), rs.getInt("stores_asked"),
                        rs.getInt("answers")),
                patient.getId());
    }

    /** One question with every store's answer, best first: see {@link Comparison}. */
    @Transactional(readOnly = true)
    public Comparison compare(UUID patientUserId, UUID requestId) {
        Patient patient = requirePatient(patientUserId);
        RequestHeader header = header(requestId)
                .filter(h -> h.patientId().equals(patient.getId()))
                .orElseThrow(() -> new NotFoundException("Medicine request", requestId));
        List<Item> items = items(requestId);
        Map<UUID, List<AnswerLine>> lines = answerLines(requestId, null);

        Instant now = clock.instant();
        List<StoreAnswer> answers = jdbc.query("""
                SELECT rr.store_id, rr.distance_m, rr.status, rr.note, rr.answered_at, rr.answered_automatically,
                       s.name, s.address_line, s.phone, s.opens_at, s.closes_at, s.open_24h, s.time_zone,
                       s.hold_hours
                FROM request_recipients rr JOIN stores s ON s.id = rr.store_id
                WHERE rr.request_id = ?
                """, (rs, i) -> {
                    UUID storeId = rs.getObject("store_id", UUID.class);
                    boolean open24h = rs.getBoolean("open_24h");
                    boolean openNow = com.medicity.store.StoreHours.isOpen(open24h,
                            rs.getTime("opens_at").toLocalTime(), rs.getTime("closes_at").toLocalTime(),
                            now.atZone(java.time.ZoneId.of(rs.getString("time_zone"))));
                    List<AnswerLine> storeLines = lines.getOrDefault(storeId, List.of());
                    return StoreAnswer.of(storeId, rs.getString("name"), rs.getString("address_line"),
                            rs.getString("phone"), rs.getInt("distance_m"), openNow, rs.getInt("hold_hours"),
                            "ANSWERED".equals(rs.getString("status")), rs.getBoolean("answered_automatically"),
                            rs.getString("note"), ts(rs.getTimestamp("answered_at")), storeLines, items.size());
                }, requestId);

        return new Comparison(header.withStatus(effectiveStatus(header.status(), header.expiresAt())),
                items, Comparison.rank(answers));
    }

    /** Open questions past their lifetime stop being open. Run by the expiry job. */
    @Transactional
    public int expireOverdue() {
        Timestamp now = Timestamp.from(clock.instant());
        return jdbc.update("""
                UPDATE medicine_requests SET status = 'EXPIRED', closed_at = ?
                WHERE status = 'OPEN' AND expires_at <= ?
                """, now, now);
    }

    /**
     * The prescription behind a question, for a store it was sent to; 404 for
     * any other store, audited, exactly as for reading the question.
     */
    @Transactional(readOnly = true)
    public UUID prescriptionForStore(UUID ownerUserId, UUID requestId) {
        Store store = storeService.requireOwn(ownerUserId);
        if (recipient(requestId, store.getId()).isEmpty()) {
            auditLog.recordIndependently("ACCESS_DENIED", "MEDICINE_REQUEST", requestId,
                    AuditLog.Outcome.DENIED, Map.of("operation", "photo", "storeId", store.getId()));
            throw new NotFoundException("Medicine request", requestId);
        }
        return header(requestId).orElseThrow().prescriptionId();
    }

    // --- the store's side ----------------------------------------------------

    /** The store's queue: questions waiting for it, or ones it answered. */
    @Transactional(readOnly = true)
    public List<QueueEntry> queue(UUID ownerUserId, boolean answered) {
        Store store = storeService.requireOwn(ownerUserId);
        Instant now = clock.instant();
        return jdbc.query("""
                SELECT r.id, r.created_at, r.expires_at, r.status AS request_status, rr.status, rr.distance_m,
                       rr.answered_at, coalesce(pu.full_name, p.full_name) AS patient_name, du.full_name AS doctor_name,
                       (SELECT count(*) FROM medicine_request_items i WHERE i.request_id = r.id) AS medicines
                FROM request_recipients rr
                JOIN medicine_requests r ON r.id = rr.request_id
                JOIN patients p ON p.id = r.patient_id
                LEFT JOIN users pu ON pu.id = p.user_id
                JOIN prescriptions rx ON rx.id = r.prescription_id
                JOIN doctors d ON d.id = rx.doctor_id
                JOIN users du ON du.id = d.user_id
                WHERE rr.store_id = ?
                  AND (%s)
                ORDER BY %s
                LIMIT 50
                """.formatted(
                        answered ? "rr.status = 'ANSWERED'"
                                : "rr.status = 'PENDING' AND r.status = 'OPEN' AND r.expires_at > ?",
                        answered ? "rr.answered_at DESC" : "r.created_at"),
                (rs, i) -> new QueueEntry(
                        rs.getObject("id", UUID.class), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(),
                        effectiveStatus(rs.getString("request_status"), rs.getTimestamp("expires_at").toInstant()),
                        rs.getString("status"), ts(rs.getTimestamp("answered_at")), rs.getInt("distance_m"),
                        shortName(rs.getString("patient_name")), rs.getString("doctor_name"), rs.getInt("medicines")),
                answered ? new Object[]{store.getId()} : new Object[]{store.getId(), Timestamp.from(now)});
    }

    /**
     * A question as the store sees it, with the prescription it came from.
     * Only a store the question was sent to can read it; any other gets 404,
     * and the attempt is audited. Every read is audited: the patient can see
     * which stores looked at their prescription.
     */
    @Transactional(readOnly = true)
    public StoreView forStore(UUID ownerUserId, UUID requestId) {
        Store store = storeService.requireOwn(ownerUserId);
        Recipient recipient = recipient(requestId, store.getId()).orElseGet(() -> {
            auditLog.recordIndependently("ACCESS_DENIED", "MEDICINE_REQUEST", requestId,
                    AuditLog.Outcome.DENIED, Map.of("operation", "store-view", "storeId", store.getId()));
            throw new NotFoundException("Medicine request", requestId);
        });
        RequestHeader header = header(requestId).orElseThrow();

        List<StoreItem> items = jdbc.query("""
                SELECT i.medicine_id, i.quantity, i.substitution_allowed,
                       m.name, m.generic_name, m.strength, m.form,
                       pi.dosage, pi.frequency, pi.duration_days
                FROM medicine_request_items i
                JOIN medicines m ON m.id = i.medicine_id
                JOIN medicine_requests r ON r.id = i.request_id
                JOIN prescription_items pi ON pi.prescription_id = r.prescription_id AND pi.medicine_id = i.medicine_id
                WHERE i.request_id = ?
                ORDER BY m.name
                """, (rs, i) -> new StoreItem(
                        rs.getObject("medicine_id", UUID.class), rs.getString("name"), rs.getString("generic_name"),
                        rs.getString("strength"), rs.getString("form"), rs.getInt("quantity"),
                        rs.getBoolean("substitution_allowed"), rs.getString("dosage"), rs.getString("frequency"),
                        rs.getInt("duration_days"), List.of()), requestId);
        items = items.stream().map(i -> i.substitutionAllowed() ? i.withEquivalents(equivalents(i.medicineId())) : i)
                .toList();

        Verification rx = jdbc.queryForObject("""
                SELECT du.full_name AS doctor_name, d.specialization, d.license_number, rx.issued_at,
                       (rx.supersedes_id IS NOT NULL) AS revised, (rx.scan_id IS NOT NULL) AS has_photo,
                       (SELECT dispensed_at FROM prescription_dispensations pd WHERE pd.prescription_id = rx.id)
                           AS hospital_dispensed_at
                FROM prescriptions rx
                JOIN doctors d ON d.id = rx.doctor_id
                JOIN users du ON du.id = d.user_id
                WHERE rx.id = ?
                """, (rs, i) -> new Verification(rs.getString("doctor_name"), rs.getString("specialization"),
                        rs.getString("license_number"), rs.getTimestamp("issued_at").toInstant(),
                        rs.getBoolean("revised"), ts(rs.getTimestamp("hospital_dispensed_at")),
                        rs.getBoolean("has_photo")),
                header.prescriptionId());

        auditLog.recordIndependently("MEDICINE_REQUEST_VIEWED", "MEDICINE_REQUEST", requestId,
                AuditLog.Outcome.SUCCESS, Map.of("storeId", store.getId()));

        return new StoreView(requestId, effectiveStatus(header.status(), header.expiresAt()), header.createdAt(),
                header.expiresAt(), shortName(header.patientName()), recipient.distanceM(), rx, items,
                recipient.status(), recipient.note(), recipient.answeredAt(),
                answerLines(requestId, store.getId()).getOrDefault(store.getId(), List.of()));
    }

    /**
     * The store's answer: one line per medicine asked for. Once only, and only
     * while the question is open.
     *
     * <p>The guard is one conditional UPDATE of the store's own recipient row,
     * from PENDING, joined to the question still being open. Two tabs of the
     * same store answering at once both run it; one row changes, the other
     * gets 0 and a 409. Nothing is read first that could go stale.
     */
    @Transactional
    public void answer(UUID ownerUserId, UUID requestId, String note, List<LineInput> lines) {
        Store store = storeService.requireOwn(ownerUserId);
        answerAs(store.getId(), store.getName(), requestId, note, lines, false);
    }

    /** The store's answer, typed in by the chemist or computed from its live stock. */
    private void answerAs(UUID storeId, String storeName, UUID requestId, String note, List<LineInput> lines,
                          boolean automatic) {
        Recipient recipient = recipient(requestId, storeId).orElseGet(() -> {
            auditLog.recordIndependently("ACCESS_DENIED", "MEDICINE_REQUEST", requestId,
                    AuditLog.Outcome.DENIED, Map.of("operation", "answer", "storeId", storeId));
            throw new NotFoundException("Medicine request", requestId);
        });
        Map<UUID, Item> asked = items(requestId).stream()
                .collect(Collectors.toMap(Item::medicineId, i -> i));
        validate(lines, asked);

        Instant now = clock.instant();
        int claimed = jdbc.update("""
                UPDATE request_recipients rr
                SET status = 'ANSWERED', answered_at = ?, note = ?, answered_automatically = ?
                FROM medicine_requests r
                WHERE rr.request_id = ? AND rr.store_id = ? AND rr.status = 'PENDING'
                  AND r.id = rr.request_id AND r.status = 'OPEN' AND r.expires_at > ?
                """, Timestamp.from(now), blankToNull(note), automatic, requestId, storeId, Timestamp.from(now));
        if (claimed == 0) {
            if ("ANSWERED".equals(recipient.status()) || recipient(requestId, storeId)
                    .map(r -> "ANSWERED".equals(r.status())).orElse(false)) {
                throw new ConflictException("ALREADY_ANSWERED", "Your store has already answered this question");
            }
            throw new ConflictException("QUESTION_CLOSED",
                    "The patient has closed this question, or it expired, so it can no longer be answered");
        }

        jdbc.batchUpdate("""
                INSERT INTO request_answer_lines (request_id, store_id, medicine_id, availability,
                                                  quantity_available, unit_price, substitute_medicine_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, lines.stream().map(l -> new Object[]{requestId, storeId, l.medicineId(),
                l.availability().name(), quantityFor(l, asked.get(l.medicineId())),
                l.availability() == Availability.NO ? null : l.unitPrice(),
                l.availability() == Availability.NO ? null : l.substituteMedicineId()}).toList());

        long available = lines.stream().filter(l -> l.availability() != Availability.NO).count();
        RequestHeader header = header(requestId).orElseThrow();
        auditLog.recordChange("MEDICINE_REQUEST_ANSWERED", "MEDICINE_REQUEST", requestId,
                Map.of("storeId", storeId, "available", available, "asked", asked.size(), "automatic", automatic));
        outbox.publish(Outbox.STORE_ANSWERED, requestId, Map.of(
                "patientUserId", header.patientUserId(),
                "storeName", storeName,
                "available", available,
                "asked", asked.size(),
                "complete", lines.stream().allMatch(l -> l.availability() == Availability.YES)));
    }

    /**
     * An answer computed from live stock: the prescribed brand if there is
     * enough; else, where the doctor allowed it, the cheapest other brand of
     * the same medicine with enough; else as many of the prescribed brand as
     * there are.
     */
    List<LineInput> fromStock(List<Item> asked, Map<UUID, StoreStockService.StockLine> stock) {
        List<LineInput> lines = new ArrayList<>();
        for (Item item : asked) {
            StoreStockService.StockLine own = stock.get(item.medicineId());
            int have = own == null ? 0 : own.quantity();
            if (have >= item.quantity()) {
                lines.add(new LineInput(item.medicineId(), Availability.YES, null, own.unitPrice(), null));
                continue;
            }
            Optional<StoreStockService.StockLine> other = !item.substitutionAllowed() ? Optional.empty()
                    : equivalents(item.medicineId()).stream()
                    .map(e -> stock.get(e.id()))
                    .filter(Objects::nonNull)
                    .filter(l -> l.quantity() >= item.quantity())
                    .min(Comparator.comparing(StoreStockService.StockLine::unitPrice));
            if (other.isPresent()) {
                lines.add(new LineInput(item.medicineId(), Availability.YES, null, other.get().unitPrice(),
                        other.get().medicineId()));
            } else if (have > 0) {
                lines.add(new LineInput(item.medicineId(), Availability.PARTIAL, have, own.unitPrice(), null));
            } else {
                lines.add(new LineInput(item.medicineId(), Availability.NO, null, null, null));
            }
        }
        return lines;
    }

    private String storeName(UUID storeId) {
        return jdbc.queryForObject("SELECT name FROM stores WHERE id = ?", String.class, storeId);
    }

    // --- validation ---------------------------------------------------------

    private void validate(List<LineInput> lines, Map<UUID, Item> asked) {
        Set<UUID> answered = new HashSet<>();
        for (LineInput line : lines) {
            Item item = asked.get(line.medicineId());
            if (item == null) {
                throw new ValidationException("NOT_ASKED", "That medicine is not on this question");
            }
            if (!answered.add(line.medicineId())) {
                throw new ValidationException("DUPLICATE_ANSWER", "Answer each medicine once");
            }
            if (line.availability() == Availability.NO) {
                continue;
            }
            if (line.unitPrice() == null || line.unitPrice().signum() < 0
                    || line.unitPrice().compareTo(MAX_UNIT_PRICE) > 0) {
                throw new ValidationException("PRICE_REQUIRED", "Give a price for every medicine you have");
            }
            if (line.availability() == Availability.PARTIAL
                    && (line.quantity() == null || line.quantity() < 1 || line.quantity() >= item.quantity())) {
                throw new ValidationException("INVALID_QUANTITY",
                        "For 'partly', say how many you have: between 1 and %d".formatted(item.quantity() - 1));
            }
            if (line.substituteMedicineId() != null) {
                if (!item.substitutionAllowed()) {
                    throw new ValidationException("SUBSTITUTION_NOT_ALLOWED",
                            "The doctor did not allow another brand of " + item.name());
                }
                if (!equivalents(item.medicineId()).stream().map(Equivalent::id).toList()
                        .contains(line.substituteMedicineId())) {
                    throw new ValidationException("NOT_EQUIVALENT",
                            "The replacement must be the same medicine and strength as " + item.name());
                }
            }
        }
        if (answered.size() != asked.size()) {
            throw new ValidationException("INCOMPLETE_ANSWER", "Answer every medicine on the question");
        }
    }

    private static int quantityFor(LineInput line, Item item) {
        return switch (line.availability()) {
            case YES -> item.quantity();
            case PARTIAL -> line.quantity();
            case NO -> 0;
        };
    }

    // --- reads ----------------------------------------------------------------

    /** Other brands a chemist may give instead: same ingredient, strength and form. */
    List<Equivalent> equivalents(UUID medicineId) {
        return jdbc.query("""
                SELECT m.id, m.name, m.strength, m.unit_price
                FROM medicines m JOIN medicines original ON original.id = ?
                WHERE m.id <> original.id AND m.active
                  AND lower(m.generic_name) = lower(original.generic_name)
                  AND coalesce(m.strength, '') = coalesce(original.strength, '')
                  AND m.form = original.form
                ORDER BY m.unit_price, m.name
                """, (rs, i) -> new Equivalent(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("strength"), rs.getBigDecimal("unit_price")), medicineId);
    }

    private Optional<RequestHeader> header(UUID requestId) {
        return jdbc.query("""
                SELECT r.id, r.patient_id, coalesce(p.user_id, p.guardian_user_id) AS patient_user_id, coalesce(pu.full_name, p.full_name) AS patient_name,
                       r.prescription_id, r.status, r.created_at, r.expires_at, r.stores_asked, r.radius_m,
                       du.full_name AS doctor_name, rx.diagnosis
                FROM medicine_requests r
                JOIN patients p ON p.id = r.patient_id
                LEFT JOIN users pu ON pu.id = p.user_id
                JOIN prescriptions rx ON rx.id = r.prescription_id
                JOIN doctors d ON d.id = rx.doctor_id
                JOIN users du ON du.id = d.user_id
                WHERE r.id = ?
                """, (rs, i) -> new RequestHeader(
                        rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("patient_user_id", UUID.class), rs.getString("patient_name"),
                        rs.getObject("prescription_id", UUID.class), rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                        rs.getInt("stores_asked"), rs.getInt("radius_m"), rs.getString("doctor_name"),
                        rs.getString("diagnosis")),
                requestId).stream().findFirst();
    }

    private List<Item> items(UUID requestId) {
        return jdbc.query("""
                SELECT i.medicine_id, i.quantity, i.substitution_allowed, m.name, m.generic_name, m.strength, m.form
                FROM medicine_request_items i JOIN medicines m ON m.id = i.medicine_id
                WHERE i.request_id = ?
                ORDER BY m.name
                """, (rs, i) -> new Item(rs.getObject("medicine_id", UUID.class), rs.getString("name"),
                rs.getString("generic_name"), rs.getString("strength"), rs.getString("form"),
                rs.getInt("quantity"), rs.getBoolean("substitution_allowed")), requestId);
    }

    /** Answer lines per store, for one store when {@code storeId} is given. */
    private Map<UUID, List<AnswerLine>> answerLines(UUID requestId, UUID storeId) {
        return jdbc.query("""
                SELECT l.store_id, l.medicine_id, l.availability, l.quantity_available, l.unit_price,
                       l.substitute_medicine_id, sm.name AS substitute_name, sm.strength AS substitute_strength
                FROM request_answer_lines l
                LEFT JOIN medicines sm ON sm.id = l.substitute_medicine_id
                WHERE l.request_id = ? AND (CAST(? AS uuid) IS NULL OR l.store_id = CAST(? AS uuid))
                """, (rs, i) -> Map.entry(rs.getObject("store_id", UUID.class), new AnswerLine(
                        rs.getObject("medicine_id", UUID.class), Availability.valueOf(rs.getString("availability")),
                        rs.getInt("quantity_available"), rs.getBigDecimal("unit_price"),
                        rs.getObject("substitute_medicine_id", UUID.class), rs.getString("substitute_name"),
                        rs.getString("substitute_strength"))),
                requestId, storeId, storeId).stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    private Optional<Recipient> recipient(UUID requestId, UUID storeId) {
        return jdbc.query("""
                SELECT distance_m, status, note, answered_at FROM request_recipients
                WHERE request_id = ? AND store_id = ?
                """, (rs, i) -> new Recipient(rs.getInt("distance_m"), rs.getString("status"),
                rs.getString("note"), ts(rs.getTimestamp("answered_at"))), requestId, storeId).stream().findFirst();
    }

    private String statusOfOwn(UUID requestId, UUID patientId) {
        return jdbc.query("SELECT status, expires_at FROM medicine_requests WHERE id = ? AND patient_id = ?",
                        (rs, i) -> effectiveStatus(rs.getString("status"), rs.getTimestamp("expires_at").toInstant()),
                        requestId, patientId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Medicine request", requestId));
    }

    private Patient requirePatient(UUID userId) {
        return acting.resolve(userId);
    }

    /** OPEN past its expiry reads as EXPIRED, before the job has written it. */
    private String effectiveStatus(String status, Instant expiresAt) {
        return "OPEN".equals(status) && !expiresAt.isAfter(clock.instant()) ? "EXPIRED" : status;
    }

    private List<PrescriptionItem> choose(Prescription rx, Collection<UUID> medicineIds) {
        if (rx.getItems().isEmpty()) {
            throw new ValidationException("NOTHING_TO_ASK", "This prescription lists no medicines");
        }
        if (medicineIds == null || medicineIds.isEmpty()) {
            return rx.getItems();
        }
        List<PrescriptionItem> chosen = rx.getItems().stream()
                .filter(i -> medicineIds.contains(i.getMedicine().getId())).toList();
        if (chosen.size() != new HashSet<>(medicineIds).size()) {
            throw new ValidationException("NOT_ON_PRESCRIPTION", "Ask only about medicines on this prescription");
        }
        return chosen;
    }

    /** "Meera N.": a store answering a question does not need the patient's full name. */
    static String shortName(String fullName) {
        String[] parts = fullName.trim().split("\\s+");
        return parts.length == 1 ? parts[0] : parts[0] + " " + parts[parts.length - 1].charAt(0) + ".";
    }

    private static String formatRadius(int radiusM) {
        return radiusM < 1000 ? radiusM + " m" : (radiusM % 1000 == 0 ? radiusM / 1000 : radiusM / 1000.0) + " km";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    // --- shapes -----------------------------------------------------------------

    public enum Availability { YES, PARTIAL, NO }

    public record LineInput(UUID medicineId, Availability availability, Integer quantity, BigDecimal unitPrice,
                            UUID substituteMedicineId) {}

    record RequestHeader(UUID id, UUID patientId, UUID patientUserId, String patientName, UUID prescriptionId,
                         String status, Instant createdAt, Instant expiresAt, int storesAsked, int radiusM,
                         String doctorName, String diagnosis) {
        RequestHeader withStatus(String s) {
            return new RequestHeader(id, patientId, patientUserId, patientName, prescriptionId, s, createdAt,
                    expiresAt, storesAsked, radiusM, doctorName, diagnosis);
        }
    }

    record Recipient(int distanceM, String status, String note, Instant answeredAt) {}

    public record Item(UUID medicineId, String name, String genericName, String strength, String form,
                       int quantity, boolean substitutionAllowed) {}

    public record Equivalent(UUID id, String name, String strength, BigDecimal listPrice) {}

    public record AnswerLine(UUID medicineId, Availability availability, int quantityAvailable, BigDecimal unitPrice,
                             UUID substituteMedicineId, String substituteName, String substituteStrength) {}

    public record RequestSummary(UUID id, String status, Instant createdAt, Instant expiresAt, UUID prescriptionId,
                                 String diagnosis, String doctorName, int medicines, int storesAsked, int answers) {}

    public record QueueEntry(UUID id, Instant createdAt, Instant expiresAt, String requestStatus, String myStatus,
                             Instant answeredAt, int distanceM, String patientName, String doctorName,
                             int medicines) {}

    /** How the store knows the prescription is real: who wrote it, in Medicity, and when. */
    public record Verification(String doctorName, String specialization, String doctorRegistration,
                               Instant issuedAt, boolean revised, Instant hospitalDispensedAt,
                               /** The doctor's handwritten original can be viewed. */
                               boolean hasPhoto) {}

    public record StoreItem(UUID medicineId, String name, String genericName, String strength, String form,
                            int quantity, boolean substitutionAllowed, String dosage, String frequency,
                            int durationDays, List<Equivalent> equivalents) {
        StoreItem withEquivalents(List<Equivalent> e) {
            return new StoreItem(medicineId, name, genericName, strength, form, quantity, substitutionAllowed,
                    dosage, frequency, durationDays, e);
        }
    }

    public record StoreView(UUID id, String status, Instant createdAt, Instant expiresAt, String patientName,
                            int distanceM, Verification prescription, List<StoreItem> items, String myStatus,
                            String myNote, Instant answeredAt, List<AnswerLine> myAnswer) {}
}
