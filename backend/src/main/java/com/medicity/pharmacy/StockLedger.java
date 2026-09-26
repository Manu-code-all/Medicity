package com.medicity.pharmacy;

import com.medicity.audit.AuditLog;
import com.medicity.common.ConflictException;
import com.medicity.common.NotFoundException;
import com.medicity.common.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * The only supported way to change pharmacy stock.
 *
 * <h2>The same hazard, a different answer</h2>
 *
 * Dispensing has the identical time-of-check/time-of-use problem as appointment
 * booking, but the fix that works there does not apply here. Booking contends
 * over an <em>identity</em> (this slot), so a unique index can arbitrate.
 * Dispensing contends over a <em>count</em>, and uniqueness says nothing useful
 * about a count.
 *
 * <p>The guard is therefore a {@code CHECK (quantity_on_hand >= 0)} constraint
 * paired with a conditional UPDATE that carries its own predicate:
 *
 * <pre>{@code UPDATE medicine_stock
 *    SET quantity_on_hand = quantity_on_hand - :n
 *  WHERE medicine_id = :id AND quantity_on_hand >= :n}</pre>
 *
 * <p>The row lock the UPDATE takes serialises concurrent dispensers for the
 * duration of the statement, so the comparison and the write cannot be split.
 * The affected-row count is the answer: 1 means the stock was ours, 0 means
 * somebody else got there first. No {@code SELECT} precedes it, so there is no
 * window to lose.
 *
 * <p>The CHECK constraint is belt and braces — it makes a negative quantity
 * unrepresentable even if some future code path forgets the predicate.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StockLedger {

    private final MedicineStockRepository stockRepository;
    private final StockMovementRepository movementRepository;
    private final AuditLog auditLog;

    /**
     * Removes {@code quantity} units from stock.
     *
     * @throws ConflictException if insufficient stock remains at the moment of
     *                           the write — including when another dispenser won
     *                           the race a microsecond earlier
     */
    @Transactional
    public void dispense(UUID medicineId, int quantity, UUID prescriptionId) {
        requirePositive(quantity);

        int rowsAffected = stockRepository.tryDecrement(medicineId, quantity);

        if (rowsAffected == 0) {
            // Two causes are indistinguishable from the UPDATE alone: the medicine
            // does not exist, or there was not enough stock. Only now is it worth
            // a read to tell the caller which.
            boolean exists = stockRepository.existsById(medicineId);
            if (!exists) {
                throw new NotFoundException("Medicine stock", medicineId);
            }
            log.info("Dispense refused: medicine={} requested={}", medicineId, quantity);
            throw new ConflictException("INSUFFICIENT_STOCK",
                    "Not enough stock remaining to dispense this medicine");
        }

        movementRepository.save(StockMovement.builder()
                .medicineId(medicineId)
                .delta(-quantity)
                .reason(StockMovement.Reason.DISPENSE)
                .referenceId(prescriptionId)
                .build());
    }

    @Transactional
    public void restock(UUID medicineId, int quantity, UUID purchaseOrderId) {
        requirePositive(quantity);

        if (stockRepository.increment(medicineId, quantity) == 0) {
            throw new NotFoundException("Medicine stock", medicineId);
        }

        movementRepository.save(StockMovement.builder()
                .medicineId(medicineId)
                .delta(quantity)
                .reason(StockMovement.Reason.RESTOCK)
                .referenceId(purchaseOrderId)
                .build());
        // Dispensing is audited per prescription by DispensingService; a
        // restock has no such parent, so it is audited here.
        auditLog.recordChange("STOCK_RESTOCKED", "MEDICINE", medicineId, Map.of("quantity", quantity));
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            // Without this, a negative "dispense" would silently become a restock
            // that bypasses every receiving control.
            throw new ValidationException("INVALID_QUANTITY", "Quantity must be greater than zero");
        }
    }
}
