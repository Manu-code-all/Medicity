package com.medicity.scheduling.followup;

import com.medicity.common.ForbiddenException;
import com.medicity.common.NotFoundException;
import com.medicity.common.ValidationException;
import com.medicity.outbox.Outbox;
import com.medicity.scheduling.Appointment;
import com.medicity.scheduling.AppointmentRepository;
import com.medicity.scheduling.AppointmentStatus;
import com.medicity.security.AppUserPrincipal;
import com.medicity.user.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Free follow-up questions after a visit. The patient (or the account holder
 * for a family member) may ask up to {@value #MAX_QUESTIONS} in the
 * {@link #WINDOW} after the visit ended; the doctor answers any question left
 * unanswered, even after the window closes, so no question is stranded.
 */
@Service
@RequiredArgsConstructor
public class FollowUpService {

    static final int MAX_QUESTIONS = 3;
    static final Duration WINDOW = Duration.ofDays(7);

    private final NamedParameterJdbcTemplate jdbc;
    private final AppointmentRepository appointments;
    private final Outbox outbox;
    private final Clock clock;

    public enum Sender { PATIENT, DOCTOR }

    public record Message(UUID id, Sender sender, String body, Instant sentAt) {}

    public record Thread(List<Message> messages, int questionsLeft, Instant closesAt, boolean open,
                         boolean awaitingDoctor) {}

    public Thread thread(UUID appointmentId, AppUserPrincipal caller) {
        Appointment visit = load(appointmentId);
        sideOf(visit, caller);
        return threadOf(visit);
    }

    /**
     * Adds a message from whichever side the caller is. The visit's row is
     * locked first ({@code SELECT ... FOR UPDATE}), so the count of questions
     * and the insert happen as one step: two questions sent at the same
     * moment are taken one after the other, and the second sees the first.
     */
    @Transactional
    public Thread post(UUID appointmentId, AppUserPrincipal caller, String body) {
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) {
            throw new ValidationException("EMPTY_MESSAGE", "Write your question first.");
        }
        jdbc.query("SELECT id FROM appointments WHERE id = :id FOR UPDATE", Map.of("id", appointmentId), rs -> {});
        Appointment visit = load(appointmentId);
        Sender side = sideOf(visit, caller);
        if (visit.getStatus() != AppointmentStatus.COMPLETED) {
            throw new ValidationException("FOLLOWUP_NOT_OPEN", "Follow-up questions open once the visit has taken place.");
        }
        Thread now = threadOf(visit);
        if (side == Sender.PATIENT) {
            if (!now.open()) {
                throw new ValidationException("FOLLOWUP_CLOSED",
                        "The free follow-up period for this visit has ended. Book a new visit to ask more.");
            }
            if (now.questionsLeft() == 0) {
                throw new ValidationException("FOLLOWUP_LIMIT",
                        "You have asked the %d free questions for this visit.".formatted(MAX_QUESTIONS));
            }
        } else if (!now.awaitingDoctor()) {
            throw new ValidationException("NOTHING_TO_ANSWER", "There is no question waiting for an answer.");
        }

        UUID id = jdbc.queryForObject("""
                INSERT INTO visit_followups (appointment_id, sender, author_user_id, body, created_at)
                VALUES (:visit, :sender, :author, :body, :at) RETURNING id
                """, Map.of("visit", appointmentId, "sender", side.name(), "author", caller.getId(), "body", text,
                "at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)), UUID.class);

        if (side == Sender.PATIENT) {
            outbox.publish(Outbox.FOLLOWUP_ASKED, id, Map.of(
                    "doctorUserId", visit.getSlot().getDoctor().getUser().getId(),
                    "patientName", visit.getPatient().displayName(),
                    "appointmentId", appointmentId));
        } else {
            outbox.publish(Outbox.FOLLOWUP_ANSWERED, id, Map.of(
                    "patientUserId", visit.getPatient().accountUserId(),
                    "forName", visit.getPatient().forName(),
                    "doctorName", visit.getSlot().getDoctor().getUser().getFullName()));
        }
        return threadOf(visit);
    }

    private Thread threadOf(Appointment visit) {
        List<Message> messages = jdbc.query("""
                SELECT id, sender, body, created_at FROM visit_followups
                WHERE appointment_id = :visit ORDER BY seq
                """, Map.of("visit", visit.getId()), (rs, n) -> new Message(rs.getObject("id", UUID.class),
                Sender.valueOf(rs.getString("sender")), rs.getString("body"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()));
        long asked = messages.stream().filter(m -> m.sender() == Sender.PATIENT).count();
        Instant closes = visit.getSlot().getEndsAt().plus(WINDOW);
        boolean open = visit.getStatus() == AppointmentStatus.COMPLETED && clock.instant().isBefore(closes);
        boolean awaiting = !messages.isEmpty() && messages.get(messages.size() - 1).sender() == Sender.PATIENT;
        return new Thread(messages, (int) Math.max(0, MAX_QUESTIONS - asked), closes, open, awaiting);
    }

    private Appointment load(UUID id) {
        return appointments.findByIdWithDetails(id).orElseThrow(() -> new NotFoundException("Appointment", id));
    }

    private static Sender sideOf(Appointment visit, AppUserPrincipal caller) {
        if (caller.getRole() == Role.PATIENT && caller.getId().equals(visit.getPatient().accountUserId())) {
            return Sender.PATIENT;
        }
        if (caller.getRole() == Role.DOCTOR && caller.getId().equals(visit.getSlot().getDoctor().getUser().getId())) {
            return Sender.DOCTOR;
        }
        throw new ForbiddenException("Only this visit's patient and doctor can see its follow-up questions");
    }
}
