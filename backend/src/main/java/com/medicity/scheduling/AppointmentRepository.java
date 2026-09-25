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

    /**
     * Loads one appointment with everything the authorization check and the
     * response mapper need.
     *
     * <p>Required because {@code open-in-view} is disabled: outside a
     * transaction the Hibernate session is already closed by the time a
     * controller touches {@code appointment.getSlot().getDoctor()}, and the
     * lazy proxy throws. Reading an id off a proxy happens to work, which is
     * why the patient path survived without this and the doctor path did not —
     * exactly the kind of inconsistency that surfaces only under a real
     * database.
     */
    @Query("""
            SELECT a FROM Appointment a
            JOIN FETCH a.slot s
            JOIN FETCH s.doctor d
            JOIN FETCH a.patient p
            WHERE a.id = :id
            """)
    Optional<Appointment> findByIdWithDetails(@Param("id") UUID id);

    /** The live holder of a slot, if any. Mirrors the partial unique index. */
    @Query("""
            SELECT a FROM Appointment a
            WHERE a.slot.id = :slotId
              AND a.status <> com.medicity.scheduling.AppointmentStatus.CANCELLED
            """)
    Optional<Appointment> findActiveBySlot(@Param("slotId") UUID slotId);
}
