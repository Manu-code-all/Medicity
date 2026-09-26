package com.medicity.clinical;

import com.medicity.pharmacy.Medicine;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "prescription_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class PrescriptionItem {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prescription_id", nullable = false, updatable = false)
    private Prescription prescription;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "medicine_id", nullable = false, updatable = false)
    private Medicine medicine;

    @Column(name = "dosage", nullable = false, updatable = false, length = 80)
    private String dosage;

    @Column(name = "frequency", nullable = false, updatable = false, length = 80)
    private String frequency;

    @Column(name = "duration_days", nullable = false, updatable = false)
    private int durationDays;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;
}
