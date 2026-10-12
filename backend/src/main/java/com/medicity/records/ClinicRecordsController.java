package com.medicity.records;

import com.medicity.security.AppUserPrincipal;
import com.medicity.security.OtpService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The patient's side of imported clinic records: prove the email address, see
 * which clinics hold a record for it, and say yes or no to each. Acts on the
 * signed-in account only.
 */
@RestController
@RequestMapping("/api/v1/patients/me/clinic-records")
@PreAuthorize("hasRole('PATIENT')")
@RequiredArgsConstructor
@Tag(name = "Clinic records")
public class ClinicRecordsController {

    private final ClinicRecordLinks links;
    private final OtpService otp;

    @GetMapping
    @Operation(summary = "Records clinics hold for my verified email, and the ones I accepted",
            description = "Until the email address is verified the answer is empty for everyone.")
    public ClinicRecordLinks.View view(@AuthenticationPrincipal AppUserPrincipal principal) {
        return links.view(principal.getId());
    }

    @PostMapping("/email-code")
    @Operation(summary = "Email a code to my address to prove it is mine")
    public OtpService.CodeSent sendCode(@AuthenticationPrincipal AppUserPrincipal principal) {
        return otp.sendToEmail(principal.getEmail());
    }

    @PostMapping("/email-code/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Type the code that was emailed to prove the address")
    public void verify(@AuthenticationPrincipal AppUserPrincipal principal, @Valid @RequestBody CodeRequest request) {
        otp.confirmEmail(principal.getId(), request.code());
    }

    @PostMapping("/{id}/accept")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Yes: link this clinic's record to my account and show it to me")
    public void accept(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable UUID id) {
        links.accept(principal.getId(), id);
    }

    @PostMapping("/{id}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "No: do not link it; it stays hidden and is not offered again")
    public void decline(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable UUID id) {
        links.decline(principal.getId(), id);
    }

    public record CodeRequest(@NotBlank @Size(max = 6) String code) {}
}
