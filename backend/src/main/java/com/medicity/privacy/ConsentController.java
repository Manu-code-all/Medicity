package com.medicity.privacy;

import com.medicity.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/** What the signed-in account has agreed to, and agreeing again when a text changes. */
@RestController
@RequestMapping("/api/v1/consents/me")
@RequiredArgsConstructor
@Tag(name = "Privacy")
public class ConsentController {

    private final ConsentService consents;

    @GetMapping
    @Operation(summary = "The agreements this account needs, and which are out of date",
            description = "pending is true while any current text has not been agreed to. "
                    + "The web app shows the agreement screen until it is false.")
    public ConsentService.Status status(@AuthenticationPrincipal AppUserPrincipal principal) {
        return consents.status(principal.getId(), principal.getRole());
    }

    @PostMapping
    @Operation(summary = "Agree to the current version of the given texts",
            description = "Used when a policy changes. Sign-up records its consents itself.")
    public ConsentService.Status accept(@AuthenticationPrincipal AppUserPrincipal principal,
                                        @Valid @RequestBody AcceptRequest request) {
        return consents.accept(principal.getId(), principal.getRole(), request.types());
    }

    public record AcceptRequest(@NotEmpty Set<ConsentType> types) {}
}
