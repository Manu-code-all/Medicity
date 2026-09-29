package com.medicity.video;

import com.medicity.audit.AuditLog;
import com.medicity.common.ForbiddenException;
import com.medicity.common.NotFoundException;
import com.medicity.common.ValidationException;
import com.medicity.scheduling.Appointment;
import com.medicity.scheduling.AppointmentRepository;
import com.medicity.scheduling.AppointmentStatus;
import com.medicity.scheduling.VisitType;
import com.medicity.security.AppUserPrincipal;
import com.medicity.user.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-time tickets to join a video visit's signalling socket.
 *
 * <p>A browser's WebSocket cannot send an Authorization header, and putting
 * the access token in the URL would write a fifteen-minute credential into
 * proxy and server logs. Instead an authenticated POST checks who may join
 * and returns a random ticket that works once, for sixty seconds, for that
 * visit and that side of the call. Kept in memory: the API runs as a single
 * instance (see Known gaps), and a lost ticket costs one more click.
 */
@Service
public class VideoTickets {

    static final Duration TICKET_LIFETIME = Duration.ofSeconds(60);

    private final AppointmentRepository appointments;
    private final AuditLog auditLog;
    private final Clock clock;
    private final Duration joinBefore;
    private final Duration joinAfter;
    private final List<String> iceServers;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public VideoTickets(AppointmentRepository appointments, AuditLog auditLog, Clock clock,
                        @Value("${medicity.video.join-before:PT15M}") Duration joinBefore,
                        @Value("${medicity.video.join-after:PT30M}") Duration joinAfter,
                        @Value("${medicity.video.ice-servers:stun:stun.l.google.com:19302}") List<String> iceServers) {
        this.appointments = appointments;
        this.auditLog = auditLog;
        this.clock = clock;
        this.joinBefore = joinBefore;
        this.joinAfter = joinAfter;
        this.iceServers = iceServers;
    }

    /** Which side of the call a ticket is for. */
    public enum Side { PATIENT, DOCTOR }

    record Pending(UUID appointmentId, Side side, Instant expiresAt) {}

    public record Ticket(String ticket, Side side, Instant expiresAt, List<String> iceServers,
                         Instant opensAt, Instant closesAt) {}

    /**
     * Issues a ticket if the caller is this visit's patient (or their family
     * account holder) or its doctor, the visit is a booked video visit, and
     * it is within the joining window around its time.
     */
    @Transactional
    public Ticket issue(UUID appointmentId, AppUserPrincipal caller) {
        Appointment visit = appointments.findByIdWithDetails(appointmentId)
                .orElseThrow(() -> new NotFoundException("Appointment", appointmentId));
        Side side = sideOf(visit, caller);
        if (visit.getVisitType() != VisitType.VIDEO) {
            throw new ValidationException("NOT_A_VIDEO_VISIT", "This visit is in person.");
        }
        if (visit.getStatus() != AppointmentStatus.BOOKED) {
            throw new ValidationException("VIDEO_NOT_OPEN", "This visit is no longer open.");
        }
        Instant now = clock.instant();
        Instant opens = visit.getScheduledAt().minus(joinBefore);
        Instant closes = visit.getSlot().getEndsAt().plus(joinAfter);
        if (now.isBefore(opens) || now.isAfter(closes)) {
            throw new ValidationException("VIDEO_NOT_OPEN", now.isBefore(opens)
                    ? "The video room opens %d minutes before the visit.".formatted(joinBefore.toMinutes())
                    : "The time for this video visit has passed.");
        }

        pending.values().removeIf(p -> p.expiresAt().isBefore(now));
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expires = now.plus(TICKET_LIFETIME);
        pending.put(ticket, new Pending(appointmentId, side, expires));
        auditLog.recordChange("VIDEO_JOIN_REQUESTED", "APPOINTMENT", appointmentId, Map.of("side", side.name()));
        return new Ticket(ticket, side, expires, iceServers, opens, closes);
    }

    /** Spends a ticket: valid once, until it expires. */
    Optional<Pending> redeem(String ticket) {
        if (ticket == null) {
            return Optional.empty();
        }
        Pending p = pending.remove(ticket);
        return p == null || p.expiresAt().isBefore(clock.instant()) ? Optional.empty() : Optional.of(p);
    }

    private static Side sideOf(Appointment visit, AppUserPrincipal caller) {
        if (caller.getRole() == Role.PATIENT && caller.getId().equals(visit.getPatient().accountUserId())) {
            return Side.PATIENT;
        }
        if (caller.getRole() == Role.DOCTOR && caller.getId().equals(visit.getSlot().getDoctor().getUser().getId())) {
            return Side.DOCTOR;
        }
        throw new ForbiddenException("Only this visit's patient and doctor can join its video room");
    }
}
