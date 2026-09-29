package com.medicity.scheduling.attachment;

import com.medicity.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/appointments/{id}/attachments")
@RequiredArgsConstructor
@Tag(name = "Visit records")
public class AttachmentController {

    private final AttachmentService attachments;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('PATIENT')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Attach a report or old prescription (PDF, JPEG, PNG, WebP; 5 MB; 5 per visit)")
    public AttachmentService.Attachment upload(@AuthenticationPrincipal AppUserPrincipal principal,
                                               @PathVariable UUID id,
                                               @RequestParam("file") MultipartFile file,
                                               @RequestParam(value = "note", required = false) String note) throws IOException {
        return attachments.upload(id, principal, file.getOriginalFilename(), file.getBytes(),
                note == null ? null : note.length() > 255 ? note.substring(0, 255) : note);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('PATIENT','DOCTOR')")
    @Operation(summary = "The records attached to a visit (for its patient and doctor)")
    public List<AttachmentService.Attachment> list(@AuthenticationPrincipal AppUserPrincipal principal,
                                                   @PathVariable UUID id) {
        return attachments.list(id, principal);
    }

    @GetMapping("/{attachmentId}")
    @PreAuthorize("hasAnyRole('PATIENT','DOCTOR')")
    @Operation(summary = "One attached file")
    public ResponseEntity<byte[]> open(@AuthenticationPrincipal AppUserPrincipal principal,
                                       @PathVariable UUID id, @PathVariable UUID attachmentId) {
        AttachmentService.File file = attachments.open(id, attachmentId, principal);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(file.fileName()).build().toString())
                // The stored type is the sniffed one; the browser must not guess another.
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(file.bytes());
    }

    @DeleteMapping("/{attachmentId}")
    @PreAuthorize("hasRole('PATIENT')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove an attached file before the visit")
    public void remove(@AuthenticationPrincipal AppUserPrincipal principal,
                       @PathVariable UUID id, @PathVariable UUID attachmentId) {
        attachments.remove(id, attachmentId, principal);
    }
}
