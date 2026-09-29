package com.medicity.review;

import com.medicity.audit.AuditLog;
import com.medicity.common.ConflictException;
import com.medicity.common.ForbiddenException;
import com.medicity.common.NotFoundException;
import com.medicity.common.ValidationException;
import com.medicity.scheduling.Appointment;
import com.medicity.scheduling.AppointmentRepository;
import com.medicity.scheduling.AppointmentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reviews of doctors, written only by patients who were actually seen.
 *
 * <p>Plain SQL: a review is written once and never edited, and every read
 * is an aggregate or a short list, which JPA would only make slower to read.
 */
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final NamedParameterJdbcTemplate jdbc;
    private final AppointmentRepository appointmentRepository;
    private final AuditLog auditLog;

    public record Rating(double average, int count) {}

    public record Review(int rating, String comment, String reviewer, Instant createdAt) {}

    /**
     * Records a review of a completed visit by the account that owns it (the
     * patient, or the family member's account holder). The unique index on
     * the appointment settles a double submit: the second is a 409.
     */
    @Transactional
    public void submit(UUID appointmentId, UUID accountUserId, int rating, String comment) {
        Appointment visit = appointmentRepository.findByIdWithDetails(appointmentId)
                .orElseThrow(() -> new NotFoundException("Appointment", appointmentId));
        if (!accountUserId.equals(visit.getPatient().accountUserId())) {
            throw new ForbiddenException("Only the patient seen can review this visit");
        }
        if (visit.getStatus() != AppointmentStatus.COMPLETED) {
            throw new ValidationException("NOT_REVIEWABLE", "Only a visit that took place can be reviewed");
        }
        String text = comment == null || comment.isBlank() ? null : comment.trim();
        try {
            jdbc.update("""
                    INSERT INTO doctor_reviews (appointment_id, doctor_id, patient_id, rating, comment)
                    VALUES (:appointment, :doctor, :patient, :rating, :comment)
                    """, new MapSqlParameterSource()
                    .addValue("appointment", appointmentId)
                    .addValue("doctor", visit.getSlot().getDoctor().getId())
                    .addValue("patient", visit.getPatient().getId())
                    .addValue("rating", rating)
                    .addValue("comment", text));
        } catch (DuplicateKeyException e) {
            throw new ConflictException("ALREADY_REVIEWED", "This visit has already been reviewed");
        }
        auditLog.recordChange("REVIEW_SUBMITTED", "APPOINTMENT", appointmentId, Map.of("rating", rating));
    }

    /** Average and count for each of the given doctors that has reviews; one query for a page. */
    public Map<UUID, Rating> ratings(Collection<UUID> doctorIds) {
        if (doctorIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Rating> out = new HashMap<>();
        jdbc.query("""
                SELECT doctor_id, avg(rating) AS average, count(*) AS n
                FROM doctor_reviews WHERE doctor_id IN (:ids) GROUP BY doctor_id
                """, Map.of("ids", doctorIds), rs -> {
            out.put(rs.getObject("doctor_id", UUID.class),
                    new Rating(Math.round(rs.getDouble("average") * 10) / 10.0, rs.getInt("n")));
        });
        return out;
    }

    /** Which of these visits already have a review, for the portal's "Rate this visit". */
    public Set<UUID> reviewedAmong(Collection<UUID> appointmentIds) {
        if (appointmentIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList(
                "SELECT appointment_id FROM doctor_reviews WHERE appointment_id IN (:ids)",
                Map.of("ids", appointmentIds), UUID.class));
    }

    /**
     * A doctor's latest reviews. The reviewer is shown as a first name and an
     * initial: enough to read as a person, not enough to identify a patient.
     */
    public List<Review> latest(UUID doctorId, int limit) {
        return jdbc.query("""
                SELECT r.rating, r.comment, r.created_at, COALESCE(u.full_name, p.full_name) AS name
                FROM doctor_reviews r
                JOIN patients p ON p.id = r.patient_id
                LEFT JOIN users u ON u.id = p.user_id
                WHERE r.doctor_id = :doctor
                ORDER BY r.created_at DESC
                LIMIT :limit
                """, Map.of("doctor", doctorId, "limit", limit),
                (rs, n) -> new Review(rs.getInt("rating"), rs.getString("comment"),
                        shortName(rs.getString("name")), rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()));
    }

    /** "Meera Nair" becomes "Meera N."; a single name stays as it is. */
    static String shortName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "A patient";
        }
        String[] parts = fullName.trim().split("\\s+");
        return parts.length == 1 ? parts[0] : parts[0] + " " + parts[parts.length - 1].charAt(0) + ".";
    }
}
