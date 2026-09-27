package com.medicity.scan;

import com.medicity.audit.AuditLog;
import com.medicity.clinical.PrescriptionRepository;
import com.medicity.clinical.VisitService;
import com.medicity.common.NotFoundException;
import com.medicity.doctor.DoctorRepository;
import com.medicity.patient.ActingPatient;
import com.medicity.request.MedicineRequestService;
import com.medicity.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Photos of handwritten prescriptions: the doctor uploads and reads them; the
 * patient and the stores the patient asked can look at the original.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Prescription photos")
public class ScanController {

    private final ScanService scans;
    private final DoctorRepository doctorRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final VisitService visitService;
    private final ActingPatient acting;
    private final MedicineRequestService requests;
    private final AuditLog auditLog;

    @PostMapping(value = "/api/v1/doctors/me/visits/{appointmentId}/scans", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('DOCTOR')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload a photo of my handwritten prescription for a completed visit")
    public Map<String, UUID> upload(@AuthenticationPrincipal AppUserPrincipal principal,
                                    @PathVariable UUID appointmentId,
                                    @RequestParam("photo") MultipartFile photo) throws IOException {
        UUID scanId = scans.upload(appointmentId, doctorId(principal), photo.getBytes(), photo.getContentType());
        return Map.of("scanId", scanId);
    }

    @PostMapping("/api/v1/doctors/me/scans/{scanId}/read")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "Read the photo into a draft to correct and confirm. Issues nothing.")
    public ScanService.Draft read(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable UUID scanId) {
        return scans.read(scanId, doctorId(principal));
    }

    @GetMapping("/api/v1/doctors/me/scans/{scanId}/image")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "A photo I uploaded")
    public ResponseEntity<byte[]> doctorImage(@AuthenticationPrincipal AppUserPrincipal principal,
                                              @PathVariable UUID scanId) {
        return image(scans.imageForDoctor(scanId, doctorId(principal)));
    }

    @GetMapping("/api/v1/doctors/me/prescriptions/{prescriptionId}/scan")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "The photo behind a prescription from one of my visits")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> doctorPrescriptionImage(@AuthenticationPrincipal AppUserPrincipal principal,
                                                          @PathVariable UUID prescriptionId) {
        UUID appointmentId = prescriptionRepository.findById(prescriptionId)
                .orElseThrow(() -> new NotFoundException("Prescription", prescriptionId)).getAppointment().getId();
        visitService.requireOwnVisit(appointmentId, doctorId(principal));
        return image(scans.imageForPrescription(prescriptionId)
                .orElseThrow(() -> new NotFoundException("Photo for prescription", prescriptionId)));
    }

    @GetMapping("/api/v1/patients/me/prescriptions/{prescriptionId}/scan")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "The doctor's handwritten original of one of my prescriptions")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> patientImage(@AuthenticationPrincipal AppUserPrincipal principal,
                                               @PathVariable UUID prescriptionId) {
        UUID patientId = acting.resolve(principal.getId()).getId();
        boolean own = prescriptionRepository.findById(prescriptionId)
                .map(rx -> rx.getPatient().getId().equals(patientId)).orElse(false);
        if (!own) {
            throw new NotFoundException("Prescription", prescriptionId);
        }
        return image(scans.imageForPrescription(prescriptionId)
                .orElseThrow(() -> new NotFoundException("Photo for prescription", prescriptionId)));
    }

    @GetMapping("/api/v1/stores/me/requests/{requestId}/scan")
    @PreAuthorize("hasRole('CHEMIST')")
    @Operation(summary = "The doctor's handwritten original behind a question sent to my store")
    public ResponseEntity<byte[]> storeImage(@AuthenticationPrincipal AppUserPrincipal principal,
                                             @PathVariable UUID requestId) {
        UUID prescriptionId = requests.prescriptionForStore(principal.getId(), requestId);
        auditLog.recordIndependently("PRESCRIPTION_PHOTO_VIEWED", "MEDICINE_REQUEST", requestId,
                AuditLog.Outcome.SUCCESS, null);
        return image(scans.imageForPrescription(prescriptionId)
                .orElseThrow(() -> new NotFoundException("Photo for prescription", prescriptionId)));
    }

    private static ResponseEntity<byte[]> image(ScanService.Image image) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                // Medical records: never kept by a shared browser or a proxy.
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(image.bytes());
    }

    private UUID doctorId(AppUserPrincipal principal) {
        return doctorRepository.findByUserId(principal.getId())
                .orElseThrow(() -> new NotFoundException("Doctor profile for user", principal.getId())).getId();
    }
}
