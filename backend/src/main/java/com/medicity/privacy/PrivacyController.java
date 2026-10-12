package com.medicity.privacy;

import com.medicity.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * A patient's data rights: a copy of their data, and deletion. Both act on
 * the signed-in account, never on a patient id from the request, so there is
 * nothing to tamper with; the family members an account manages come with it.
 */
@RestController
@RequestMapping("/api/v1/patients/me")
@PreAuthorize("hasRole('PATIENT')")
@RequiredArgsConstructor
@Tag(name = "Privacy")
public class PrivacyController {

    private final DataExportService exports;
    private final AccountDeletionService deletion;
    private final Clock clock;

    @GetMapping("/export")
    @Operation(summary = "Download everything held about me and my family members, as JSON",
            description = "Always the whole account, whichever family member is selected in the app.")
    public ResponseEntity<Map<String, Object>> export(@AuthenticationPrincipal AppUserPrincipal principal) {
        Map<String, Object> data = exports.export(principal.getId());
        String file = "medicity-my-data-" + LocalDate.now(clock.withZone(ZoneOffset.UTC)) + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file).build().toString())
                // Personal health data: a browser or proxy must not keep a copy.
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_JSON)
                .body(data);
    }

    @PostMapping("/delete-account")
    @Operation(summary = "Delete my account",
            description = "Needs the account password again. Anonymises the account, its family members and "
                    + "personal details, ends every session and cancels upcoming visits; clinical records the "
                    + "law requires are kept without a name. Cannot be undone.")
    public Map<String, Integer> deleteAccount(@AuthenticationPrincipal AppUserPrincipal principal,
                                              @Valid @RequestBody DeleteRequest request) {
        return deletion.delete(principal.getId(), request.password());
    }

    public record DeleteRequest(@NotBlank @Size(max = 128) String password) {}
}
