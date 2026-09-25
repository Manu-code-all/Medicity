package com.medicity.pharmacy;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only movement log. Every change to on-hand quantity writes one row, so
 * the current balance is always reconstructable and any discrepancy is traceable
 * to a cause rather than appearing as an unexplained number.
 */
@Entity
@Table(name = "stock_movements")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    @Column(name = "medicine_id", nullable = false)
    private UUID medicineId;

    /** Negative for a dispense, positive for a restock. Never zero. */
    @Column(name = "delta", nullable = false)
    private int delta;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 32)
    private Reason reason;

    /** The prescription, order or adjustment that caused this movement. */
    @Column(name = "reference_id")
    private UUID referenceId;

    @Builder.Default
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    public enum Reason { RESTOCK, DISPENSE, ADJUSTMENT, EXPIRY, RETURN }
}
