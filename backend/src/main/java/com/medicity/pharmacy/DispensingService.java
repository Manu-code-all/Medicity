package com.medicity.pharmacy;

import com.medicity.audit.AuditLog;
import com.medicity.clinical.Prescription;
import com.medicity.clinical.PrescriptionItem;
import com.medicity.clinical.PrescriptionRepository;
import com.medicity.common.ConflictException;
import com.medicity.common.Constraints;
import com.medicity.common.NotFoundException;
import com.medicity.common.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fills a prescription: every medicine on it, or none of them.
 *
 * <p>Three guarantees, each enforced by the database rather than by a check
 * that could race:
 * <ol>
 *   <li><b>At most once.</b> A dispensation row is inserted first; its unique
 *       constraint makes a second, concurrent attempt fail before any stock moves.</li>
 *   <li><b>All or nothing.</b> Every stock decrement runs in this one
 *       transaction. If any medicine is short, the exception rolls back the
 *       decrements already made and the dispensation row with them.</li>
 *   <li><b>Never below zero.</b> Each decrement is the conditional UPDATE in
 *       {@link StockLedger}, backed by a CHECK constraint.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DispensingService {

    private static final String UQ_DISPENSATION = "uq_dispensation_prescription";

    private final PrescriptionRepository prescriptionRepository;
    private final DispensationRepository dispensationRepository;
    private final MedicineStockRepository stockRepository;
    private final StockLedger stockLedger;
    private final AuditLog auditLog;
    private final Clock clock;

    @Transactional
    public DispenseResult dispense(UUID prescriptionId, UUID dispensedBy) {
        Prescription prescription = prescriptionRepository.findWithItems(prescriptionId)
                .orElseThrow(() -> new NotFoundException("Prescription", prescriptionId));

        // A corrected prescription replaces the original. Filling the original
        // would hand the patient the instructions the doctor withdrew.
        if (prescriptionRepository.existsBySupersedesId(prescriptionId)) {
            throw new ValidationException("PRESCRIPTION_SUPERSEDED",
                    "This prescription was corrected. Dispense the corrected version instead.");
        }
        if (prescription.getItems().isEmpty()) {
            throw new ValidationException("NOTHING_TO_DISPENSE", "This prescription lists no medicines");
        }

        Instant now = clock.instant();
        try {
            // Before any stock moves, so a losing concurrent attempt changes nothing.
            dispensationRepository.saveAndFlush(Dispensation.builder()
                    .prescriptionId(prescriptionId)
                    .dispensedBy(dispensedBy)
                    .dispensedAt(now)
                    .build());
        } catch (DataIntegrityViolationException e) {
            if (Constraints.isViolationOf(e, UQ_DISPENSATION)) {
                throw new ConflictException("ALREADY_DISPENSED", "This prescription has already been dispensed");
            }
            throw e;
        }

        // Lock stock rows in one global order. Two prescriptions sharing
        // medicines, decremented in opposite orders, would each hold one row
        // lock and wait for the other's: a deadlock. A fixed order rules it out.
        List<PrescriptionItem> items = prescription.getItems().stream()
                .sorted(Comparator.comparing(item -> item.getMedicine().getId()))
                .toList();

        for (PrescriptionItem item : items) {
            try {
                stockLedger.dispense(item.getMedicine().getId(), item.getQuantity(), prescriptionId);
            } catch (ConflictException e) {
                // Rethrown with the medicine named, so the counter knows what is short.
                // The transaction is already marked for rollback: nothing dispensed sticks.
                throw new ConflictException("INSUFFICIENT_STOCK", "Not enough %s %s in stock to dispense %d"
                        .formatted(item.getMedicine().getName(), nullToEmpty(item.getMedicine().getStrength()),
                                item.getQuantity()));
            }
        }

        auditLog.recordChange("PRESCRIPTION_DISPENSED", "PRESCRIPTION", prescriptionId,
                Map.of("patientId", prescription.getPatient().getId(), "items", items.size()));
        log.info("Prescription {} dispensed ({} items)", prescriptionId, items.size());

        return new DispenseResult(prescriptionId, now, items.stream()
                .map(item -> new DispensedLine(
                        item.getMedicine().getName(),
                        item.getMedicine().getStrength(),
                        item.getQuantity(),
                        stockRepository.findById(item.getMedicine().getId())
                                .map(MedicineStock::getQuantityOnHand).orElse(0)))
                .toList());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    public record DispenseResult(UUID prescriptionId, Instant dispensedAt, List<DispensedLine> items) {}

    public record DispensedLine(String medicine, String strength, int quantity, int remainingStock) {}
}
