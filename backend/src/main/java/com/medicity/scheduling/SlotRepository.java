package com.medicity.scheduling;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SlotRepository extends JpaRepository<AppointmentSlot, UUID> {

    /**
     * Availability search: OPEN slots in a window that no live appointment holds.
     *
     * <p>Note this is a read-only convenience for rendering the calendar. It is
     * NOT the source of truth for whether a booking will succeed — by the time
     * the user clicks, another patient may have taken the slot. The authoritative
     * answer comes from the unique index at INSERT time. Treating a search result
     * as a reservation is precisely the bug this system is designed to avoid.
     */
    @Query("""
            SELECT s FROM AppointmentSlot s
            WHERE s.doctor.id = :doctorId
              AND s.doctor.verifiedAt IS NOT NULL
              AND s.status = com.medicity.scheduling.SlotStatus.OPEN
              AND s.startsAt >= :from
              AND s.startsAt <  :to
              AND NOT EXISTS (
                    SELECT 1 FROM Appointment a
                    WHERE a.slot = s
                      AND a.status <> com.medicity.scheduling.AppointmentStatus.CANCELLED
              )
            ORDER BY s.startsAt
            """)
    List<AppointmentSlot> findAvailable(@Param("doctorId") UUID doctorId,
                                        @Param("from") Instant from,
                                        @Param("to") Instant to);

    /**
     * Ids of the first {@code perDoctor} open slots of each doctor on a page of
     * the directory, in one query rather than one per card: a window function
     * numbers each doctor's slots in time order and keeps the first few. Same
     * rules as {@link #findAvailable}; the (doctor_id, starts_at) index serves it.
     * Only ids come back, so the caller loads typed entities instead of mapping
     * driver-specific timestamp types from a native row.
     */
    @Query(value = """
            SELECT id
            FROM (
                SELECT s.id, s.doctor_id, s.starts_at, s.ends_at,
                       row_number() OVER (PARTITION BY s.doctor_id ORDER BY s.starts_at) AS n
                FROM appointment_slots s
                JOIN doctors d ON d.id = s.doctor_id AND d.verified_at IS NOT NULL
                WHERE s.doctor_id IN (:doctorIds)
                  AND s.status = 'OPEN'
                  AND s.starts_at >= :from
                  AND s.starts_at <  :to
                  AND NOT EXISTS (
                        SELECT 1 FROM appointments a
                        WHERE a.slot_id = s.id AND a.status <> 'CANCELLED')
            ) numbered
            WHERE n <= :perDoctor
            """, nativeQuery = true)
    List<UUID> findNextAvailableIds(@Param("doctorIds") Collection<UUID> doctorIds,
                                     @Param("from") Instant from,
                                     @Param("to") Instant to,
                                     @Param("perDoctor") int perDoctor);
}
