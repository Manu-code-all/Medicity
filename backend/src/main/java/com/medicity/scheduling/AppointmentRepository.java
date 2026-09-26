package com.medicity.scheduling;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
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

    /**
     * Upcoming visits: still booked and not yet started. Soonest first, because
     * the question a patient is asking here is "what is next?".
     *
     * <p>{@code countQuery} is given explicitly because Spring Data cannot derive
     * a count from a query containing JOIN FETCH.
     */
    @Query(value = """
            SELECT a FROM Appointment a
            JOIN FETCH a.slot s
            JOIN FETCH s.doctor d
            JOIN FETCH d.user
            WHERE a.patient.id = :patientId
              AND a.status = com.medicity.scheduling.AppointmentStatus.BOOKED
              AND a.scheduledAt >= :now
            ORDER BY a.scheduledAt ASC
            """,
            countQuery = """
            SELECT count(a) FROM Appointment a
            WHERE a.patient.id = :patientId
              AND a.status = com.medicity.scheduling.AppointmentStatus.BOOKED
              AND a.scheduledAt >= :now
            """)
    Page<Appointment> findUpcomingForPatient(@Param("patientId") UUID patientId,
                                             @Param("now") Instant now,
                                             Pageable pageable);

    /**
     * History: everything that is not upcoming — completed, cancelled, missed,
     * and bookings whose time has passed without being closed. The exact
     * complement of {@link #findUpcomingForPatient}, so no visit appears in both
     * lists or in neither.
     */
    @Query(value = """
            SELECT a FROM Appointment a
            JOIN FETCH a.slot s
            JOIN FETCH s.doctor d
            JOIN FETCH d.user
            WHERE a.patient.id = :patientId
              AND (a.status <> com.medicity.scheduling.AppointmentStatus.BOOKED
                   OR a.scheduledAt < :now)
            ORDER BY a.scheduledAt DESC
            """,
            countQuery = """
            SELECT count(a) FROM Appointment a
            WHERE a.patient.id = :patientId
              AND (a.status <> com.medicity.scheduling.AppointmentStatus.BOOKED
                   OR a.scheduledAt < :now)
            """)
    Page<Appointment> findPastForPatient(@Param("patientId") UUID patientId,
                                         @Param("now") Instant now,
                                         Pageable pageable);

    /** Per-status totals for the portal overview, in one grouped query. */
    @Query("""
            SELECT a.status AS status, count(a) AS total FROM Appointment a
            WHERE a.patient.id = :patientId
            GROUP BY a.status
            """)
    List<StatusCount> countByStatusForPatient(@Param("patientId") UUID patientId);

    interface StatusCount {
        AppointmentStatus getStatus();
        long getTotal();
    }

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
            JOIN FETCH d.user
            JOIN FETCH a.patient p
            JOIN FETCH p.user
            WHERE a.id = :id
            """)
    Optional<Appointment> findByIdWithDetails(@Param("id") UUID id);

    /**
     * A doctor's calendar between two instants, earliest first. The caller
     * passes the bounds of "today" in its own time zone, so the server never
     * has to guess where the doctor is.
     */
    @Query("""
            SELECT a FROM Appointment a
            JOIN FETCH a.slot s
            JOIN FETCH a.patient p
            JOIN FETCH p.user
            WHERE s.doctor.id = :doctorId
              AND a.scheduledAt >= :from AND a.scheduledAt < :to
            ORDER BY a.scheduledAt ASC
            """)
    List<Appointment> findForDoctorBetween(@Param("doctorId") UUID doctorId,
                                           @Param("from") Instant from,
                                           @Param("to") Instant to);

    /** Whether this doctor has ever had this patient on their calendar. */
    boolean existsBySlotDoctorIdAndPatientId(UUID doctorId, UUID patientId);

    /** The live holder of a slot, if any. Mirrors the partial unique index. */
    @Query("""
            SELECT a FROM Appointment a
            WHERE a.slot.id = :slotId
              AND a.status <> com.medicity.scheduling.AppointmentStatus.CANCELLED
            """)
    Optional<Appointment> findActiveBySlot(@Param("slotId") UUID slotId);
}
