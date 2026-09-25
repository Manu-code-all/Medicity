package com.medicity.pharmacy;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * On-hand quantity for one medicine.
 *
 * <p>Deliberately a separate table from {@link Medicine} rather than a column on
 * it. Stock is the single hottest, most frequently written value in the pharmacy
 * domain; keeping it out of the catalogue row means a dispense does not contend
 * with anyone reading or editing product details, and the narrow row keeps the
 * update cheap.
 */
@Entity
@Table(name = "medicine_stock")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MedicineStock {

    /** Shares the medicine's id; the relationship is strictly one-to-one. */
    @Id
    @Column(name = "medicine_id", updatable = false, nullable = false)
    private UUID medicineId;

    @Builder.Default
    @Column(name = "quantity_on_hand", nullable = false)
    private int quantityOnHand = 0;

    @Builder.Default
    @Column(name = "reorder_level", nullable = false)
    private int reorderLevel = 10;

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public boolean needsReorder() {
        return quantityOnHand <= reorderLevel;
    }
}
