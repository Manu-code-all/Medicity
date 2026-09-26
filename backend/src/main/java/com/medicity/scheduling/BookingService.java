package com.medicity.scheduling;

import com.medicity.common.ConflictException;
import com.medicity.common.NotFoundException;
import com.medicity.common.ValidationException;
import com.medicity.patient.Patient;
import com.medicity.patient.PatientRepository;
import com.medicity.audit.AuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Appointment booking — the concurrency-critical path of the platform.
 *
 * <h2>Why this class exists in this shape</h2>
 *
 * The obvious implementation is:
 *
 * <pre>{@code
 * if (appointmentRepo.findActiveBySlot(slotId).isPresent()) {
 *     throw new ConflictException(...);          // "already booked"
 * }
 * appointmentRepo.save(newAppointment);          // <-- race window
 * }</pre>
 *
 * That code is wrong, and it is wrong in a way that passes every single-threaded
 * test. Two requests for the same slot can both execute the {@code findActiveBySlot}
 * check before either reaches the {@code save}. Both see "free". Both insert.
 * Two patients arrive at the clinic for the same 10:00 consultation.
 *
 * <p>Raising the isolation level does not fix it either: under Postgres's default
 * READ COMMITTED the two transactions genuinely cannot see each other's
 * uncommitted insert, and even SERIALIZABLE would resolve this by aborting one
 * transaction — which is the same outcome as the constraint, reached more
 * expensively and with more surprising failure modes elsewhere in the app.
 *
 * <h2>The approach taken</h2>
 *
 * The invariant lives in the database as a partial unique index
 * ({@code uq_active_appointment_per_slot}, migration V2). Postgres evaluates it
 * atomically: the second concurrent INSERT blocks on the index until the first
 * transaction resolves, then either proceeds (first one rolled back) or raises
 * {@code unique_violation}. There is no window.
 *
 * <p>The pre-checks below are therefore NOT the safety mechanism. They exist to
 * turn the common, uncontended failures ("that slot is blocked", "that time is in
 * the past") into clear 422s without paying for a failed INSERT. The race itself
 * is handled by catching the constraint violation and translating it. This is the
 * standard "optimistic insert, let the database arbitrate" pattern, and it scales
 * better than pessimistic locking because readers never block.
 *
 * @see com.medicity.scheduling.SlotBookingConcurrencyTest for the proof that
 *      N concurrent bookings of one slot yield exactly one winner.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    /**
     * Constraint names from migration V2. Matching on these rather than on the
     * exception message keeps the translation stable across driver versions.
     */
    private static final String UQ_ACTIVE_APPOINTMENT_PER_SLOT = "uq_active_appointment_per_slot";
    private static final String UQ_PATIENT_ACTIVE_AT_TIME      = "uq_patient_active_at_time";

    /** Bookings must be made at least this far ahead, to allow for travel. */
    private static final Duration MIN_LEAD_TIME = Duration.ofMinutes(30);

    /** Free cancellation window; later cancellations still succeed but are flagged. */
    private static final Duration FREE_CANCELLATION_WINDOW = Duration.ofHours(4);

    private final AppointmentRepository appointmentRepository;
    private final SlotRepository slotRepository;
    private final PatientRepository patientRepository;
    private final AuditLog auditLog;

    /** Injected rather than using {@code Instant.now()} so tests control time. */
    private final Clock clock;

    /**
     * Books {@code slotId} for {@code patientId}.
     *
     * @return the created appointment
     * @throws ConflictException   if another patient won the slot, or this patient
     *                             already has an appointment at that instant
     * @throws ValidationException if the slot is blocked or too close to now
     */
    @Transactional
    public Appointment book(UUID slotId, UUID patientId, String reason) {
        Instant now = clock.instant();

        AppointmentSlot slot = slotRepository.findById(slotId)
                .orElseThrow(() -> new NotFoundException("Slot", slotId));

        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() -> new NotFoundException("Patient", patientId));

        // --- Fast-path rejections. Not safety checks; see the class javadoc. ---
        if (slot.getStatus() != SlotStatus.OPEN) {
            throw new ValidationException("SLOT_NOT_OPEN",
                    "This slot is not available for booking");
        }
        if (slot.getStartsAt().isBefore(now.plus(MIN_LEAD_TIME))) {
            throw new ValidationException("SLOT_TOO_SOON",
                    "Appointments must be booked at least %d minutes in advance"
                            .formatted(MIN_LEAD_TIME.toMinutes()));
        }

        Appointment appointment = Appointment.builder()
                .slot(slot)
                .patient(patient)
                .status(AppointmentStatus.BOOKED)
                .reason(reason)
                .scheduledAt(slot.getStartsAt())
                .build();

        try {
            // saveAndFlush, not save: flush forces the INSERT to hit the database
            // inside this try block. With a plain save(), Hibernate would defer the
            // statement to transaction commit — which happens AFTER this method
            // returns, outside the catch, surfacing as an opaque 500 instead of a
            // clean 409. This one word is the difference between the error handling
            // below working and being dead code.
            Appointment saved = appointmentRepository.saveAndFlush(appointment);
            log.info("Appointment {} booked: slot={} patient={}", saved.getId(), slotId, patientId);
            // Same transaction as the insert: reached only if the insert
            // succeeded, and rolled back with it if the commit fails.
            auditLog.recordChange("APPOINTMENT_BOOKED", "APPOINTMENT", saved.getId(),
                    Map.of("slotId", slotId, "patientId", patientId, "scheduledAt", saved.getScheduledAt()));
            return saved;

        } catch (DataIntegrityViolationException e) {
            String constraint = constraintNameOf(e);

            if (UQ_ACTIVE_APPOINTMENT_PER_SLOT.equalsIgnoreCase(constraint)) {
                // Lost the race. This is an expected outcome under load, not an
                // error condition — log at INFO so it does not pollute alerting.
                log.info("Slot {} lost race for patient {}", slotId, patientId);
                throw new ConflictException("SLOT_ALREADY_BOOKED",
                        "This slot was just booked by someone else. Please choose another time.");
            }
            if (UQ_PATIENT_ACTIVE_AT_TIME.equalsIgnoreCase(constraint)) {
                throw new ConflictException("PATIENT_DOUBLE_BOOKED",
                        "You already have an appointment at this time.");
            }
            // An integrity violation we did not anticipate is a bug, not a race.
            log.error("Unmapped constraint violation on booking (constraint={})", constraint, e);
            throw e;
        }
    }

    /**
     * Cancels an appointment, freeing its slot for rebooking.
     *
     * <p>The partial unique index is scoped to non-cancelled rows, so flipping the
     * status is all that is needed — no slot bookkeeping, and no window in which
     * the slot appears both taken and free.
     */
    @Transactional
    public Appointment cancel(UUID appointmentId, String reason) {
        Instant now = clock.instant();

        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new NotFoundException("Appointment", appointmentId));

        if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
            // Idempotent: a retried cancel should not be an error.
            return appointment;
        }
        if (appointment.getStatus() == AppointmentStatus.COMPLETED) {
            throw new ValidationException("ALREADY_COMPLETED",
                    "A completed appointment cannot be cancelled");
        }

        boolean late = Duration.between(now, appointment.getScheduledAt()).compareTo(FREE_CANCELLATION_WINDOW) < 0;
        if (late) {
            log.info("Late cancellation of appointment {} ({} before start)",
                    appointmentId, Duration.between(now, appointment.getScheduledAt()));
        }

        appointment.cancel(now, reason);
        Appointment saved = appointmentRepository.save(appointment);
        auditLog.recordChange("APPOINTMENT_CANCELLED", "APPOINTMENT", appointmentId,
                Map.of("lateCancellation", late, "reason", reason == null ? "" : reason));
        return saved;
    }

    /**
     * Digs the constraint name out of the exception chain.
     *
     * <p>Spring wraps Hibernate's {@link ConstraintViolationException}, which is
     * where the name actually lives; the Spring-level exception only carries a
     * formatted message.
     */
    private String constraintNameOf(DataIntegrityViolationException e) {
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof ConstraintViolationException cve) {
                return cve.getConstraintName();
            }
            cause = cause.getCause();
        }
        return null;
    }
}
