package com.medicity.pharmacy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface MedicineStockRepository extends JpaRepository<MedicineStock, UUID> {

    /**
     * Atomically decrements stock, refusing to go below zero.
     *
     * <p>The guard is inside the UPDATE, not in Java. Reading the quantity,
     * comparing it, and writing it back would leave a window in which two
     * dispensers both observe "1 remaining" and both sell it. Here the database
     * takes a row lock for the duration of the statement, so the comparison and
     * the write are one indivisible step: the second dispenser sees the already
     * decremented value and its WHERE clause fails.
     *
     * <p>A native query is used because JPQL cannot express a conditional update
     * whose predicate references the column being assigned.
     *
     * @return 1 if the stock was decremented, 0 if there was not enough
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE medicine_stock
               SET quantity_on_hand = quantity_on_hand - :quantity,
                   updated_at = now()
             WHERE medicine_id = :medicineId
               AND quantity_on_hand >= :quantity
            """, nativeQuery = true)
    int tryDecrement(@Param("medicineId") UUID medicineId, @Param("quantity") int quantity);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE medicine_stock
               SET quantity_on_hand = quantity_on_hand + :quantity,
                   updated_at = now()
             WHERE medicine_id = :medicineId
            """, nativeQuery = true)
    int increment(@Param("medicineId") UUID medicineId, @Param("quantity") int quantity);

    /**
     * Stock at or below its reorder level, emptiest first. Served by the
     * partial index {@code idx_stock_below_reorder} from V4.
     */
    @Query("""
            SELECT m.id AS medicineId, m.name AS name, m.strength AS strength,
                   s.quantityOnHand AS quantityOnHand, s.reorderLevel AS reorderLevel
            FROM MedicineStock s JOIN Medicine m ON m.id = s.medicineId
            WHERE s.quantityOnHand <= s.reorderLevel AND m.active = true
            ORDER BY s.quantityOnHand, m.name
            """)
    List<LowStock> findAtOrBelowReorderLevel();

    interface LowStock {
        UUID getMedicineId();
        String getName();
        String getStrength();
        int getQuantityOnHand();
        int getReorderLevel();
    }
}
