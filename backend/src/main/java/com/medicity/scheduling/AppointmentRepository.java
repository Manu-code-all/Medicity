package com.medicity.scheduling;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    @Query("""
            SELECT a FROM Appointment a
            JOIN FETCH a.slot s
            JOIN FETCH s.doctor d
            JOIN FETCH d.user
            WHERE a.patient.id = :patientId
            ORDER BY a.scheduledAt DESC
            """)
    Page<Appointment> findForPatient(@Param("patientId") UUID patientId, Pageable pageable);

    @Query("""
            SELECT a FROM Appointment a
            JOIN FETCH a.patient p
            JOIN FETCH p.user
            JOIN FETCH a.slot s
            WHERE s.doctor.id = :doctorId
            ORDER BY a.scheduledAt DESC
            """)
    Page<Appointment> findForDoctor(@Param("doctorId") UUID doctorId, Pageable pageable);

    /** The live holder of a slot, if any. Mirrors the partial unique index. */
    @Query("""
            SELECT a FROM Appointment a
            WHERE a.slot.id = :slotId
              AND a.status <> com.medicity.scheduling.AppointmentStatus.CANCELLED
            """)
    Optional<Appointment> findActiveBySlot(@Param("slotId") UUID slotId);
}
