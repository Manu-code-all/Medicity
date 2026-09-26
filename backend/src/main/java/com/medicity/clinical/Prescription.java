package com.medicity.clinical;

import com.medicity.doctor.Doctor;
import com.medicity.patient.Patient;
import com.medicity.scheduling.Appointment;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A clinical document issued against an appointment.
 *
 * <p>Append-only, so this does not extend {@code BaseEntity}: there is no
 * {@code updated_at} and no {@code @Version}, because a prescription is never
 * modified after it is issued. A correction is a new row whose
 * {@code supersedesId} points at the one it replaces (see V3).
 */
@Entity
@Table(name = "prescriptions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Prescription {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "appointment_id", nullable = false, updatable = false)
    private Appointment appointment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "doctor_id", nullable = false, updatable = false)
    private Doctor doctor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false, updatable = false)
    private Patient patient;

    @Column(name = "diagnosis", nullable = false, updatable = false, length = 500)
    private String diagnosis;

    @Column(name = "notes", updatable = false, columnDefinition = "text")
    private String notes;

    /** Plain id rather than an association: only ever compared, never navigated. */
    @Column(name = "supersedes_id", updatable = false)
    private UUID supersedesId;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Builder.Default
    @OneToMany(mappedBy = "prescription", cascade = CascadeType.PERSIST)
    @OrderBy("id")
    private List<PrescriptionItem> items = new ArrayList<>();
}
