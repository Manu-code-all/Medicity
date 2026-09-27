package com.medicity.scan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicity.audit.AuditLog;
import com.medicity.clinical.VisitService;
import com.medicity.common.NotFoundException;
import com.medicity.common.ValidationException;
import com.medicity.scan.PrescriptionReader.ReadLine;
import com.medicity.scan.PrescriptionReader.Reading;
import com.medicity.scheduling.Appointment;
import com.medicity.scheduling.AppointmentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;

/**
 * Photos of handwritten prescriptions, and turning them into a draft.
 *
 * <p>Uploading and reading are separate steps, and neither writes a
 * prescription. The doctor issues the prescription through the normal form,
 * naming the photo it came from; see {@link #attach}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScanService {

    static final int MAX_BYTES = 5 * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final VisitService visitService;
    private final PrescriptionReader reader;
    private final MedicineMatcher matcher;
    private final AuditLog auditLog;
    private final ObjectMapper json;
    private final Clock clock;

    /** Stores the photo against one of the doctor's completed visits. */
    @Transactional
    public UUID upload(UUID appointmentId, UUID doctorId, byte[] image, String declaredType) {
        Appointment visit = visitService.requireOwnVisit(appointmentId, doctorId);
        if (visit.getStatus() != AppointmentStatus.COMPLETED) {
            throw new ValidationException("VISIT_NOT_COMPLETED", "Mark the visit as completed before prescribing");
        }
        if (image.length == 0 || image.length > MAX_BYTES) {
            throw new ValidationException("IMAGE_SIZE", "The photo must be under 5 MB");
        }
        // The browser's declared type is a claim; the file's first bytes are the fact.
        String type = sniff(image);
        if (type == null) {
            throw new ValidationException("NOT_AN_IMAGE", "Upload a JPEG, PNG or WebP photo");
        }
        if (declaredType != null && !declaredType.isBlank() && !declaredType.equalsIgnoreCase(type)) {
            log.info("Scan declared {} but is {}", declaredType, type);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO prescription_scans (id, appointment_id, doctor_id, content_type, image, sha256, uploaded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, appointmentId, doctorId, type, image, sha256(image), Timestamp.from(clock.instant()));
        auditLog.recordChange("PRESCRIPTION_SCAN_UPLOADED", "PRESCRIPTION_SCAN", id,
                Map.of("appointmentId", appointmentId, "bytes", image.length));
        return id;
    }

    /**
     * Reads the photo into a draft for the doctor to correct. Writes the
     * reading on the scan for the record, and nothing else.
     */
    @Transactional
    public Draft read(UUID scanId, UUID doctorId) {
        Scan scan = requireOwn(scanId, doctorId);
        if (!reader.available()) {
            jdbc.update("UPDATE prescription_scans SET reading_status = 'UNAVAILABLE' WHERE id = ?", scanId);
            return Draft.unavailable(scanId);
        }
        Reading reading;
        try {
            reading = reader.read(image(scanId), scan.contentType());
        } catch (PrescriptionReader.ReadingFailed e) {
            log.warn("Reading scan {} failed: {}", scanId, e.getMessage());
            jdbc.update("UPDATE prescription_scans SET reading_status = 'FAILED', read_at = ? WHERE id = ?",
                    Timestamp.from(clock.instant()), scanId);
            return Draft.failed(scanId, e.getMessage());
        }
        jdbc.update("""
                UPDATE prescription_scans SET reading = CAST(? AS jsonb), reading_status = 'DRAFTED', read_at = ?
                WHERE id = ?
                """, toJson(reading), Timestamp.from(clock.instant()), scanId);
        auditLog.recordChange("PRESCRIPTION_SCAN_READ", "PRESCRIPTION_SCAN", scanId,
                Map.of("lines", reading.lines().size(), "unreadable", reading.unreadable().size()));

        List<DraftLine> lines = reading.lines().stream().map(l -> new DraftLine(l, matcher.match(l))).toList();
        return new Draft(scanId, "DRAFTED", null, reading.diagnosis(), lines, reading.unreadable());
    }

    /**
     * Links an issued prescription to the photo it was typed from. Called in
     * the prescription's own transaction: the photo must be this doctor's,
     * from this visit, and not already used for another prescription (a
     * unique index decides that last race).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void attach(UUID scanId, UUID appointmentId, UUID doctorId) {
        Scan scan = requireOwn(scanId, doctorId);
        if (!scan.appointmentId().equals(appointmentId)) {
            throw new ValidationException("SCAN_FROM_ANOTHER_VISIT", "That photo belongs to a different visit");
        }
    }

    /** The photo, for the doctor who took it. */
    @Transactional(readOnly = true)
    public Image imageForDoctor(UUID scanId, UUID doctorId) {
        Scan scan = requireOwn(scanId, doctorId);
        return new Image(scan.contentType(), image(scanId));
    }

    /** The photo behind a prescription; the caller has already checked who may see it. */
    @Transactional(readOnly = true)
    public Optional<Image> imageForPrescription(UUID prescriptionId) {
        return jdbc.query("""
                SELECT s.content_type, s.image FROM prescriptions rx JOIN prescription_scans s ON s.id = rx.scan_id
                WHERE rx.id = ?
                """, (rs, i) -> new Image(rs.getString("content_type"), rs.getBytes("image")), prescriptionId)
                .stream().findFirst();
    }

    private Scan requireOwn(UUID scanId, UUID doctorId) {
        return jdbc.query("SELECT appointment_id, content_type FROM prescription_scans WHERE id = ? AND doctor_id = ?",
                        (rs, i) -> new Scan(rs.getObject("appointment_id", UUID.class), rs.getString("content_type")),
                        scanId, doctorId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Prescription photo", scanId));
    }

    private byte[] image(UUID scanId) {
        return jdbc.queryForObject("SELECT image FROM prescription_scans WHERE id = ?", byte[].class, scanId);
    }

    /** JPEG, PNG and WebP by their magic bytes; null for anything else. */
    static String sniff(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "image/png";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Reading reading) {
        try {
            return json.writeValueAsString(reading);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Scan(UUID appointmentId, String contentType) {}

    public record Image(String contentType, byte[] bytes) {}

    /** A line as read, and the catalogue medicine it matched; null when the doctor must choose. */
    public record DraftLine(ReadLine read, MedicineMatcher.Match match) {}

    public record Draft(UUID scanId, String status, String problem, String diagnosis, List<DraftLine> lines,
                        List<String> unreadable) {
        static Draft unavailable(UUID scanId) {
            return new Draft(scanId, "UNAVAILABLE",
                    "Reading handwriting is not switched on here. The photo is attached; type the medicines below.",
                    null, List.of(), List.of());
        }

        static Draft failed(UUID scanId, String why) {
            return new Draft(scanId, "FAILED", why + ". The photo is attached; type the medicines below.",
                    null, List.of(), List.of());
        }
    }
}
