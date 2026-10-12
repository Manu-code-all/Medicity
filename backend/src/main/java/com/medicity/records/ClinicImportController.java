package com.medicity.records;

import com.medicity.common.ValidationException;
import com.medicity.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * A doctor moving their old patient list in from Excel or CSV. The caller is
 * the owner of what is imported; no route takes an owner id.
 */
@RestController
@RequestMapping("/api/v1/doctors/me/import/patients")
@PreAuthorize("hasRole('DOCTOR')")
@RequiredArgsConstructor
@Tag(name = "Clinic records import")
public class ClinicImportController {

    private static final MediaType CSV = new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8);

    private final ClinicImportService imports;

    @GetMapping("/template")
    @Operation(summary = "The empty CSV to fill in (UTF-8 with a byte-order mark, opens correctly in Excel)")
    public ResponseEntity<byte[]> template() {
        return csv("medicity-patient-import-template.csv", ClinicImportService.template());
    }

    @PostMapping(value = "/dry-run", consumes = "multipart/form-data")
    @Operation(summary = "Check a CSV of patients without saving anything",
            description = "Up to 5,000 rows and 2 MB. Returns counts, what an import would create or update, and the "
                    + "first problems row by row. Keep the returned fileSha256 for the confirm step.")
    public ClinicImportService.Report dryRun(@AuthenticationPrincipal AppUserPrincipal principal,
                                             @RequestParam("file") MultipartFile file) {
        return imports.preview(principal.getId(), bytes(file));
    }

    @PostMapping(value = "/confirm", consumes = "multipart/form-data")
    @Operation(summary = "Import the rows of a CSV that passed the checks",
            description = "Send the same file as the dry run, with its fileSha256. Rows with problems are skipped. "
                    + "A patient already imported (same phone or email) is updated, not duplicated.")
    public ClinicImportService.Report confirm(@AuthenticationPrincipal AppUserPrincipal principal,
                                              @RequestParam("file") MultipartFile file,
                                              @RequestParam("fileSha256") String fileSha256) {
        return imports.confirm(principal.getId(), bytes(file), fileSha256);
    }

    @PostMapping(value = "/error-report", consumes = "multipart/form-data")
    @Operation(summary = "Every problem found in a CSV, as a CSV")
    public ResponseEntity<byte[]> errorReport(@AuthenticationPrincipal AppUserPrincipal principal,
                                              @RequestParam("file") MultipartFile file) {
        return csv("medicity-patient-import-errors.csv", imports.errorReport(principal.getId(), bytes(file)));
    }

    private static byte[] bytes(MultipartFile file) {
        if (file.getSize() > PatientCsv.MAX_BYTES) {
            throw new ValidationException("FILE_TOO_LARGE", "The file is larger than 2 MB. Split it into smaller files.");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ValidationException("UNREADABLE_FILE", "The file could not be read. Choose it again.");
        }
    }

    private static ResponseEntity<byte[]> csv(String filename, byte[] body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                // Patient details: a browser or proxy must not keep a copy.
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(CSV)
                .body(body);
    }
}
