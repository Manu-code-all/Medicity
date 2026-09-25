package com.medicity.scheduling;

import com.medicity.common.BaseEntity;
import com.medicity.patient.Patient;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "appointments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Appointment extends BaseEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id", nullable = false, updatable = false)
    private AppointmentSlot slot;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false, updatable = false)
    private Patient patient;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AppointmentStatus status = AppointmentStatus.BOOKED;

    @Column(name = "reason", length = 500)
    private String reason;

    /**
     * Copied from the slot at booking time. Slots are time-immutable, so this
     * cannot drift; it exists so the patient's appointment list sorts and
     * filters without joining {@code appointment_slots}.
     */
    @Column(name = "scheduled_at", nullable = false, updatable = false)
    private Instant scheduledAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason", length = 300)
    private String cancelReason;

    /**
     * Transitions to CANCELLED. Sets {@code cancelledAt} in the same call
     * because a database CHECK requires the two to agree — splitting them
     * across setters would let a caller commit an inconsistent row.
     */
    public void cancel(Instant when, String why) {
        this.status = AppointmentStatus.CANCELLED;
        this.cancelledAt = when;
        this.cancelReason = why;
    }
}
