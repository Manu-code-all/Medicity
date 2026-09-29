package com.medicity.queue;

import com.medicity.audit.AuditLog;
import com.medicity.common.ConflictException;
import com.medicity.common.ForbiddenException;
import com.medicity.common.NotFoundException;
import com.medicity.common.ValidationException;
import com.medicity.doctor.Doctor;
import com.medicity.doctor.DoctorRepository;
import com.medicity.outbox.Outbox;
import com.medicity.patient.Patient;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Same-day walk-in queues: a patient takes a token (#101, #102, ...) for a
 * doctor today, sees how many are ahead and roughly how long, and the
 * doctor's front desk calls them in order.
 *
 * <p>Days are India time: a clinic's "today" does not change at midnight UTC.
 * Plain SQL, like the reviews: the interesting parts (the counter, the
 * ordering, the positions) are SQL, and JPA would hide them.
 */
@Service
@RequiredArgsConstructor
public class QueueService {

    static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Kolkata");

    /** Tokens are handed out between these times; a clinic day, not all night. */
    static final LocalTime OPENS = LocalTime.of(7, 0);
    static final LocalTime CLOSES = LocalTime.of(21, 0);

    /** Minutes per patient when the doctor has not set hours for today. */
    static final int DEFAULT_MINUTES = 15;

    private final NamedParameterJdbcTemplate jdbc;
    private final DoctorRepository doctorRepository;
    private final AuditLog auditLog;
    private final Outbox outbox;
    private final Clock clock;

    public record Status(boolean open, String closedReason, int waiting, Integer nowServing,
                         int minutesPerPatient, int estimatedWaitMinutes) {}

    public record Token(UUID id, int tokenNo, String status, UUID doctorId, String doctorName, String specialization,
                        String patientName, String reason, int ahead, int estimatedWaitMinutes,
                        Instant joinedAt, Instant calledAt) {}

    /** Whether the doctor takes walk-ins now, how many are waiting, and the wait for someone joining. */
    public Status status(UUID doctorId) {
        Doctor doctor = requireBookable(doctorId);
        LocalDate today = today();
        int minutes = minutesPerPatient(doctor.getId(), today);
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE t.status = 'WAITING') AS waiting,
                       max(t.token_no) FILTER (WHERE t.status = 'CALLED') AS serving,
                       bool_or(d.closed_at IS NOT NULL) AS closed
                FROM queue_days d LEFT JOIN queue_tokens t
                  ON t.doctor_id = d.doctor_id AND t.queue_date = d.queue_date
                WHERE d.doctor_id = :doctor AND d.queue_date = :today
                """, Map.of("doctor", doctorId, "today", today));
        int waiting = row.get("waiting") == null ? 0 : ((Number) row.get("waiting")).intValue();
        Integer serving = row.get("serving") == null ? null : ((Number) row.get("serving")).intValue();
        String closedReason = Boolean.TRUE.equals(row.get("closed")) ? "The doctor has closed today's queue."
                : outsideHours() ? "Walk-in tokens are given between 7 am and 9 pm." : null;
        return new Status(closedReason == null, closedReason, waiting, serving, minutes, waiting * minutes);
    }

    /**
     * Takes the next token for {@code patient} with this doctor today.
     *
     * <p>The number comes from the day row: {@code UPDATE ... SET last_token =
     * last_token + 1 RETURNING last_token} locks that row for the increment,
     * so concurrent joins are numbered one after another. The same statement
     * refuses a closed day. If the insert then fails (the patient already
     * holds a place), the transaction rolls back, increment included, so no
     * number is skipped.
     */
    @Transactional
    public Token join(UUID doctorId, Patient patient, String reason) {
        requireBookable(doctorId);
        if (outsideHours()) {
            throw new ValidationException("QUEUE_CLOSED", "Walk-in tokens are given between 7 am and 9 pm.");
        }
        LocalDate today = today();
        var day = new MapSqlParameterSource().addValue("doctor", doctorId).addValue("today", today);
        jdbc.update("""
                INSERT INTO queue_days (doctor_id, queue_date) VALUES (:doctor, :today)
                ON CONFLICT DO NOTHING
                """, day);
        List<Integer> numbered = jdbc.queryForList("""
                UPDATE queue_days SET last_token = last_token + 1
                WHERE doctor_id = :doctor AND queue_date = :today AND closed_at IS NULL
                RETURNING last_token
                """, day, Integer.class);
        if (numbered.isEmpty()) {
            throw new ValidationException("QUEUE_CLOSED", "The doctor has closed today's queue.");
        }
        UUID id;
        try {
            id = jdbc.queryForObject("""
                    INSERT INTO queue_tokens (doctor_id, queue_date, token_no, patient_id, reason, joined_at)
                    VALUES (:doctor, :today, :no, :patient, :reason, :now)
                    RETURNING id
                    """, day.addValue("no", numbered.get(0)).addValue("patient", patient.getId())
                    .addValue("reason", reason == null || reason.isBlank() ? null : reason.trim())
                    .addValue("now", OffsetDateTime.ofInstant(clock.instant(), CLINIC_ZONE)), UUID.class);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("ALREADY_IN_QUEUE", "You already have a place in this doctor's queue today.");
        }
        auditLog.recordChange("QUEUE_JOINED", "QUEUE_TOKEN", id, Map.of("doctorId", doctorId, "tokenNo", numbered.get(0)));
        return token(id);
    }

    /** Today's tokens, still waiting or just called, for everyone this account manages. */
    public List<Token> mine(UUID accountUserId) {
        return jdbc.query(SELECT_TOKENS + """
                WHERE t.queue_date = :today
                  AND t.status IN ('WAITING', 'CALLED')
                  AND (p.user_id = :account OR p.guardian_user_id = :account)
                ORDER BY t.joined_at
                """, Map.of("today", today(), "account", accountUserId), this::mapToken);
    }

    /** Gives up a place. Only the account holder whose token it is. */
    @Transactional
    public Token leave(UUID tokenId, UUID accountUserId) {
        Token t = token(tokenId);
        Boolean owns = jdbc.queryForObject("""
                SELECT (p.user_id = :account OR p.guardian_user_id = :account)
                FROM queue_tokens t JOIN patients p ON p.id = t.patient_id WHERE t.id = :id
                """, Map.of("account", accountUserId, "id", tokenId), Boolean.class);
        if (!Boolean.TRUE.equals(owns)) {
            throw new ForbiddenException("This is not your token");
        }
        if (!t.status().equals("WAITING") && !t.status().equals("CALLED")) {
            return t;
        }
        move(tokenId, "LEFT", t.doctorId());
        return token(tokenId);
    }

    // --- front desk ---------------------------------------------------------

    /** The doctor's line for today, in token order, every status. */
    public List<Token> desk(UUID doctorId) {
        return jdbc.query(SELECT_TOKENS + """
                WHERE t.doctor_id = :doctor AND t.queue_date = :today
                ORDER BY t.token_no
                """, Map.of("doctor", doctorId, "today", today()), this::mapToken);
    }

    /**
     * Calls the lowest waiting token. {@code FOR UPDATE SKIP LOCKED}: if two
     * people at the desk press "Call next" together, each gets a different
     * patient instead of both calling the same one.
     */
    @Transactional
    public Token callNext(UUID doctorId) {
        List<UUID> next = jdbc.queryForList("""
                SELECT id FROM queue_tokens
                WHERE doctor_id = :doctor AND queue_date = :today AND status = 'WAITING'
                ORDER BY token_no
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """, Map.of("doctor", doctorId, "today", today()), UUID.class);
        if (next.isEmpty()) {
            throw new ValidationException("QUEUE_EMPTY", "Nobody is waiting.");
        }
        UUID id = next.get(0);
        jdbc.update("UPDATE queue_tokens SET status = 'CALLED', called_at = :now WHERE id = :id",
                Map.of("id", id, "now", OffsetDateTime.ofInstant(clock.instant(), CLINIC_ZONE)));
        Token called = token(id);
        UUID account = jdbc.queryForObject("""
                SELECT COALESCE(p.user_id, p.guardian_user_id) FROM queue_tokens t
                JOIN patients p ON p.id = t.patient_id WHERE t.id = :id
                """, Map.of("id", id), UUID.class);
        outbox.publish(Outbox.QUEUE_CALLED, id, Map.of(
                "patientUserId", account,
                "tokenNo", called.tokenNo(),
                "doctorName", called.doctorName(),
                "patientName", called.patientName()));
        return called;
    }

    /** Closes a called or waiting token as seen or missed. */
    @Transactional
    public Token finish(UUID doctorId, UUID tokenId, boolean seen) {
        Token t = token(tokenId);
        if (!t.doctorId().equals(doctorId)) {
            throw new ForbiddenException("This token is in another doctor's queue");
        }
        if (!t.status().equals("CALLED") && !t.status().equals("WAITING")) {
            throw new ValidationException("TOKEN_CLOSED", "This token is already closed.");
        }
        move(tokenId, seen ? "SEEN" : "MISSED", doctorId);
        return token(tokenId);
    }

    /** Stops (or resumes) handing out tokens today; those already waiting keep their place. */
    @Transactional
    public Status setOpen(UUID doctorId, boolean open) {
        var day = new MapSqlParameterSource().addValue("doctor", doctorId).addValue("today", today());
        jdbc.update("INSERT INTO queue_days (doctor_id, queue_date) VALUES (:doctor, :today) ON CONFLICT DO NOTHING", day);
        jdbc.update("UPDATE queue_days SET closed_at = " + (open ? "NULL" : "now()")
                + " WHERE doctor_id = :doctor AND queue_date = :today", day);
        return status(doctorId);
    }

    // --- helpers ------------------------------------------------------------

    private static final String SELECT_TOKENS = """
            SELECT t.id, t.token_no, t.status, t.doctor_id, t.reason, t.joined_at, t.called_at,
                   du.full_name AS doctor_name, d.specialization,
                   COALESCE(pu.full_name, p.full_name) AS patient_name,
                   (SELECT count(*) FROM queue_tokens a
                     WHERE a.doctor_id = t.doctor_id AND a.queue_date = t.queue_date
                       AND a.status = 'WAITING' AND a.token_no < t.token_no) AS ahead,
                   COALESCE((SELECT h.slot_minutes FROM doctor_hours h
                     WHERE h.doctor_id = t.doctor_id AND h.weekday = EXTRACT(ISODOW FROM t.queue_date)), %d) AS minutes
            FROM queue_tokens t
            JOIN doctors d ON d.id = t.doctor_id
            JOIN users du ON du.id = d.user_id
            JOIN patients p ON p.id = t.patient_id
            LEFT JOIN users pu ON pu.id = p.user_id
            """.formatted(DEFAULT_MINUTES);

    private Token token(UUID id) {
        List<Token> found = jdbc.query(SELECT_TOKENS + " WHERE t.id = :id", Map.of("id", id), this::mapToken);
        if (found.isEmpty()) {
            throw new NotFoundException("Queue token", id);
        }
        return found.get(0);
    }

    private Token mapToken(ResultSet rs, int n) throws SQLException {
        String status = rs.getString("status");
        int ahead = status.equals("WAITING") ? rs.getInt("ahead") : 0;
        OffsetDateTime called = rs.getObject("called_at", OffsetDateTime.class);
        return new Token(rs.getObject("id", UUID.class), rs.getInt("token_no"), status,
                rs.getObject("doctor_id", UUID.class), rs.getString("doctor_name"), rs.getString("specialization"),
                rs.getString("patient_name"), rs.getString("reason"), ahead,
                status.equals("WAITING") ? (ahead + 1) * rs.getInt("minutes") : 0,
                rs.getObject("joined_at", OffsetDateTime.class).toInstant(),
                called == null ? null : called.toInstant());
    }

    private void move(UUID tokenId, String status, UUID doctorId) {
        jdbc.update("UPDATE queue_tokens SET status = :status, finished_at = :now WHERE id = :id",
                Map.of("status", status, "id", tokenId, "now", OffsetDateTime.ofInstant(clock.instant(), CLINIC_ZONE)));
        auditLog.recordChange("QUEUE_" + status, "QUEUE_TOKEN", tokenId, Map.of("doctorId", doctorId));
    }

    private Doctor requireBookable(UUID doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new NotFoundException("Doctor", doctorId));
        if (!doctor.isVerified()) {
            throw new NotFoundException("Doctor", doctorId);
        }
        return doctor;
    }

    private int minutesPerPatient(UUID doctorId, LocalDate day) {
        List<Integer> m = jdbc.queryForList(
                "SELECT slot_minutes FROM doctor_hours WHERE doctor_id = :doctor AND weekday = :weekday",
                Map.of("doctor", doctorId, "weekday", day.getDayOfWeek().getValue()), Integer.class);
        return m.isEmpty() ? DEFAULT_MINUTES : m.get(0);
    }

    private LocalDate today() {
        return ZonedDateTime.ofInstant(clock.instant(), CLINIC_ZONE).toLocalDate();
    }

    private boolean outsideHours() {
        LocalTime now = ZonedDateTime.ofInstant(clock.instant(), CLINIC_ZONE).toLocalTime();
        return now.isBefore(OPENS) || !now.isBefore(CLOSES);
    }
}
