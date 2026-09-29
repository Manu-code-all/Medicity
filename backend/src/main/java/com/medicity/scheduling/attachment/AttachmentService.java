package com.medicity.scheduling.attachment;

import com.medicity.audit.AuditLog;
import com.medicity.common.ForbiddenException;
import com.medicity.common.NotFoundException;
import com.medicity.common.ValidationException;
import com.medicity.scheduling.Appointment;
import com.medicity.scheduling.AppointmentRepository;
import com.medicity.scheduling.AppointmentStatus;
import com.medicity.security.AppUserPrincipal;
import com.medicity.user.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reports a patient attaches to a visit for the doctor. Only the visit's
 * patient (or family account holder) uploads, while the visit is still to
 * come; only they and the visit's doctor read. The file's type is decided
 * from its first bytes, so a script renamed "report.pdf" is refused.
 */
@Service
@RequiredArgsConstructor
public class AttachmentService {

    static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_PER_VISIT = 5;

    private final NamedParameterJdbcTemplate jdbc;
    private final AppointmentRepository appointments;
    private final AuditLog auditLog;
    private final Clock clock;

    public record Attachment(UUID id, String fileName, String contentType, int sizeBytes, String note,
                             Instant uploadedAt) {}

    public record File(String fileName, String contentType, byte[] bytes) {}

    /**
     * Attaches a file. The visit's row is locked while counting, so two
     * uploads at once cannot both be the sixth.
     */
    @Transactional
    public Attachment upload(UUID appointmentId, AppUserPrincipal caller, String fileName, byte[] bytes, String note) {
        jdbc.query("SELECT id FROM appointments WHERE id = :id FOR UPDATE", Map.of("id", appointmentId), rs -> {});
        Appointment visit = load(appointmentId);
        if (!isPatient(visit, caller)) {
            throw new ForbiddenException("Only the patient can attach records to this visit");
        }
        if (visit.getStatus() != AppointmentStatus.BOOKED || !visit.getSlot().getEndsAt().isAfter(clock.instant())) {
            throw new ValidationException("ATTACH_CLOSED", "Records can be attached to an upcoming visit only.");
        }
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new ValidationException("FILE_SIZE", "The file must be under 5 MB.");
        }
        String type = sniff(bytes);
        if (type == null) {
            throw new ValidationException("FILE_TYPE", "Attach a PDF, JPEG, PNG or WebP file.");
        }
        Integer count = jdbc.queryForObject("SELECT count(*) FROM appointment_attachments WHERE appointment_id = :id",
                Map.of("id", appointmentId), Integer.class);
        if (count != null && count >= MAX_PER_VISIT) {
            throw new ValidationException("TOO_MANY_FILES", "A visit can have at most %d files.".formatted(MAX_PER_VISIT));
        }
        UUID id = jdbc.queryForObject("""
                INSERT INTO appointment_attachments
                  (appointment_id, uploader_user_id, file_name, content_type, size_bytes, data, note, uploaded_at)
                VALUES (:visit, :uploader, :name, :type, :size, :data, :note, :at)
                RETURNING id
                """, new MapSqlParameterSource().addValue("visit", appointmentId).addValue("uploader", caller.getId())
                .addValue("name", cleanName(fileName, type)).addValue("type", type).addValue("size", bytes.length)
                .addValue("data", bytes).addValue("note", note == null || note.isBlank() ? null : note.trim())
                .addValue("at", OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC)), UUID.class);
        auditLog.recordChange("ATTACHMENT_ADDED", "APPOINTMENT", appointmentId, Map.of("attachmentId", id, "type", type));
        return list(appointmentId).stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }

    public List<Attachment> list(UUID appointmentId, AppUserPrincipal caller) {
        requireReader(load(appointmentId), caller);
        return list(appointmentId);
    }

    /** The file itself. A doctor opening a patient's record is audited, as any record access is. */
    @Transactional
    public File open(UUID appointmentId, UUID attachmentId, AppUserPrincipal caller) {
        Appointment visit = load(appointmentId);
        requireReader(visit, caller);
        List<File> found = jdbc.query("""
                SELECT file_name, content_type, data FROM appointment_attachments
                WHERE id = :id AND appointment_id = :visit
                """, Map.of("id", attachmentId, "visit", appointmentId),
                (rs, n) -> new File(rs.getString("file_name"), rs.getString("content_type"), rs.getBytes("data")));
        if (found.isEmpty()) {
            throw new NotFoundException("Attachment", attachmentId);
        }
        if (caller.getRole() == Role.DOCTOR) {
            auditLog.recordChange("ATTACHMENT_VIEWED", "APPOINTMENT", appointmentId,
                    Map.of("attachmentId", attachmentId, "patientId", visit.getPatient().getId()));
        }
        return found.get(0);
    }

    /** The patient removes a file they attached, while the visit is still to come. */
    @Transactional
    public void remove(UUID appointmentId, UUID attachmentId, AppUserPrincipal caller) {
        Appointment visit = load(appointmentId);
        if (!isPatient(visit, caller)) {
            throw new ForbiddenException("Only the patient can remove records from this visit");
        }
        if (visit.getStatus() != AppointmentStatus.BOOKED) {
            throw new ValidationException("ATTACH_CLOSED", "Records of a past visit stay with it.");
        }
        jdbc.update("DELETE FROM appointment_attachments WHERE id = :id AND appointment_id = :visit",
                Map.of("id", attachmentId, "visit", appointmentId));
    }

    private List<Attachment> list(UUID appointmentId) {
        return jdbc.query("""
                SELECT id, file_name, content_type, size_bytes, note, uploaded_at FROM appointment_attachments
                WHERE appointment_id = :visit ORDER BY uploaded_at, id
                """, Map.of("visit", appointmentId), (rs, n) -> new Attachment(rs.getObject("id", UUID.class),
                rs.getString("file_name"), rs.getString("content_type"), rs.getInt("size_bytes"), rs.getString("note"),
                rs.getObject("uploaded_at", OffsetDateTime.class).toInstant()));
    }

    /** PDF, JPEG, PNG or WebP by their signatures; anything else is refused. */
    static String sniff(byte[] b) {
        if (starts(b, 0x25, 0x50, 0x44, 0x46, 0x2D)) return "application/pdf";                  // %PDF-
        if (starts(b, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
        if (starts(b, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return "image/png";
        if (b.length >= 12 && starts(b, 0x52, 0x49, 0x46, 0x46)
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }

    private static boolean starts(byte[] b, int... sig) {
        if (b.length < sig.length) return false;
        for (int i = 0; i < sig.length; i++) {
            if ((b[i] & 0xFF) != sig[i]) return false;
        }
        return true;
    }

    /** A display name only: path parts and odd characters removed, the right extension kept. */
    static String cleanName(String original, String type) {
        String base = original == null ? "" : original.replaceAll(".*[/\\\\]", "");
        base = base.replaceAll("[^A-Za-z0-9 ._()-]", "_").trim();
        if (base.isEmpty() || base.startsWith(".")) {
            base = "record";
        }
        if (base.length() > 100) {
            base = base.substring(0, 100);
        }
        String ext = switch (type) {
            case "application/pdf" -> ".pdf";
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            default -> ".webp";
        };
        // Whatever extension it came with, it leaves with the one matching its real type.
        return base.replaceAll("\\.[A-Za-z0-9]{1,5}$", "") + ext;
    }

    private Appointment load(UUID id) {
        return appointments.findByIdWithDetails(id).orElseThrow(() -> new NotFoundException("Appointment", id));
    }

    private static boolean isPatient(Appointment visit, AppUserPrincipal caller) {
        return caller.getRole() == Role.PATIENT && caller.getId().equals(visit.getPatient().accountUserId());
    }

    private static void requireReader(Appointment visit, AppUserPrincipal caller) {
        boolean doctor = caller.getRole() == Role.DOCTOR
                && caller.getId().equals(visit.getSlot().getDoctor().getUser().getId());
        if (!isPatient(visit, caller) && !doctor) {
            throw new ForbiddenException("Only this visit's patient and doctor can see its records");
        }
    }
}
