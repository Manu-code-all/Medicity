package com.medicity.scheduling;

import com.medicity.common.BaseEntity;
import com.medicity.doctor.Doctor;
import jakarta.persistence.*;
import lombok.*;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A bookable window in a doctor's calendar.
 *
 * <p>Slots are immutable in time once created: changing {@code startsAt} on a
 * slot that already has an appointment would silently move a patient's
 * booking. Rescheduling is modelled as cancel + book, which leaves an audit
 * trail. Only {@code status} may change after creation.
 */
@Entity
@Table(name = "appointment_slots")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentSlot extends BaseEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "doctor_id", nullable = false, updatable = false)
    private Doctor doctor;

    @Column(name = "starts_at", nullable = false, updatable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false, updatable = false)
    private Instant endsAt;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SlotStatus status = SlotStatus.OPEN;

    public Duration duration() {
        return Duration.between(startsAt, endsAt);
    }

    public boolean isInPast(Instant now) {
        return !startsAt.isAfter(now);
    }
}
