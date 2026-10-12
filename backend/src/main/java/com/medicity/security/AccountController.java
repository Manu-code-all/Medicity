package com.medicity.security;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in person's own password. Not under {@code /auth}: that prefix is
 * open to anonymous callers, and this needs a session.
 *
 * <p>It is also the one route an account with a temporary password may call
 * ({@link JwtAuthenticationFilter}).
 */
@RestController
@RequestMapping("/api/v1/account")
@RequiredArgsConstructor
@Tag(name = "Account")
public class AccountController {

    private final AuthService authService;

    @PostMapping("/change-password")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Change the caller's password",
            description = "Ends every session the account holds and returns a fresh token pair. "
                    + "An account created with a temporary password can do nothing else until this succeeds.")
    public AuthService.TokenPair changePassword(@AuthenticationPrincipal AppUserPrincipal principal,
                                                @Valid @RequestBody ChangePasswordRequest request) {
        return authService.changePassword(principal.getId(), request.currentPassword(), request.newPassword());
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 5, max = 128, message = "Password must be at least 5 characters") String newPassword) {}
}
