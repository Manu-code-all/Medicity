package com.medicity.clinical;

import com.medicity.audit.AuditLog;
import com.medicity.common.ForbiddenException;
import com.medicity.common.NotFoundException;
import com.medicity.common.ValidationException;
import com.medicity.scheduling.Appointment;
import com.medicity.scheduling.AppointmentRepository;
import com.medicity.scheduling.AppointmentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Recording what happened at a visit: the patient was seen, or did not come.
 *
 * <p>Both transitions are allowed only from {@code BOOKED}, only by the doctor
 * whose calendar the visit is on, and only once the visit's start time has
 * passed: a visit cannot be "completed" before it began.
 *
 * <p>Concurrency: a patient may cancel at the same moment the doctor completes.
 * Both load the row at the same {@code version}; whichever writes second
 * updates zero rows ({@code WHERE version = ?}) and fails with an optimistic
 * locking exception, answered as 409. This is the one place in the system
 * where {@code @Version} is the guard, because it is a read-decide-write on one
 * existing row rather than a race to insert.
 */
@Service
@RequiredArgsConstructor
public class VisitService {

    private final AppointmentRepository appointmentRepository;
    private final AuditLog auditLog;
    private final Clock clock;

    @Transactional
    public Appointment complete(UUID appointmentId, UUID doctorId) {
        return transition(appointmentId, doctorId, AppointmentStatus.COMPLETED, "VISIT_COMPLETED");
    }

    @Transactional
    public Appointment markNoShow(UUID appointmentId, UUID doctorId) {
        return transition(appointmentId, doctorId, AppointmentStatus.NO_SHOW, "VISIT_NO_SHOW");
    }

    /**
     * Loads a visit and checks it is on this doctor's calendar. Denials are
     * audited: a doctor opening another doctor's patient is worth knowing about.
     */
    @Transactional(readOnly = true)
    public Appointment requireOwnVisit(UUID appointmentId, UUID doctorId) {
        Appointment visit = appointmentRepository.findByIdWithDetails(appointmentId)
                .orElseThrow(() -> new NotFoundException("Visit", appointmentId));
        if (!visit.getSlot().getDoctor().getId().equals(doctorId)) {
            auditLog.recordIndependently("ACCESS_DENIED", "APPOINTMENT", appointmentId,
                    AuditLog.Outcome.DENIED, Map.of("operation", "doctor-workspace"));
            throw new ForbiddenException("You do not have access to this visit");
        }
        return visit;
    }

    private Appointment transition(UUID appointmentId, UUID doctorId, AppointmentStatus target, String action) {
        Appointment visit = requireOwnVisit(appointmentId, doctorId);

        if (visit.getStatus() != AppointmentStatus.BOOKED) {
            throw new ValidationException("INVALID_VISIT_STATE",
                    "This visit is already %s".formatted(visit.getStatus().name().toLowerCase().replace('_', ' ')));
        }
        if (visit.getScheduledAt().isAfter(clock.instant())) {
            throw new ValidationException("VISIT_NOT_STARTED",
                    "A visit can only be closed once its start time has passed");
        }

        visit.setStatus(target);
        // Flush now so a concurrent change surfaces as an optimistic-lock
        // failure inside this call, not later at commit.
        Appointment saved = appointmentRepository.saveAndFlush(visit);
        auditLog.recordChange(action, "APPOINTMENT", appointmentId,
                Map.of("patientId", visit.getPatient().getId()));
        return saved;
    }
}
