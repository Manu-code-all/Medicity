package com.medicity.scheduling;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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
}
