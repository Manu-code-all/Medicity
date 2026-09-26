package com.medicity.pharmacy;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** The record that a prescription was filled. At most one per prescription (V7). */
@Entity
@Table(name = "prescription_dispensations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Dispensation {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "prescription_id", nullable = false, updatable = false)
    private UUID prescriptionId;

    @Column(name = "dispensed_by", updatable = false)
    private UUID dispensedBy;

    @Column(name = "dispensed_at", nullable = false, updatable = false)
    private Instant dispensedAt;
}
