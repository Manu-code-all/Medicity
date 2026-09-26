package com.medicity.security;

import com.medicity.patient.Patient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication")
@SecurityRequirements   // these endpoints are the way you obtain a token
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a patient account")
    public AuthService.TokenPair register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(
                request.email(), request.password(), request.fullName(),
                request.phone(), request.dateOfBirth(), request.gender());
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange credentials for a token pair")
    public AuthService.TokenPair login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new token pair",
            description = "Each refresh token works once. Presenting a used one ends the whole session.")
    public AuthService.TokenPair refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    /** Takes the refresh token rather than a bearer token, so an expired access token can still sign out. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "End the session the refresh token belongs to")
    public void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,

            // Length is the single strongest password control. Composition rules
            // ("must contain a symbol") push users toward predictable patterns
            // without adding real entropy, so they are deliberately omitted —
            // this follows NIST SP 800-63B rather than folklore.
            @NotBlank @Size(min = 12, max = 128,
                    message = "Password must be at least 12 characters") String password,

            @NotBlank @Size(max = 120) String fullName,

            @Pattern(regexp = "^$|^\\+?[0-9]{10,15}$",
                    message = "Phone must be 10-15 digits") String phone,

            @NotNull @Past(message = "Date of birth must be in the past") LocalDate dateOfBirth,

            Patient.Gender gender
    ) {}

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}
}
