package com.medicity.security;

import com.medicity.patient.Patient;
import com.medicity.privacy.ConsentService;
import com.medicity.user.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication")
@SecurityRequirements   // these endpoints are the way you obtain a token
public class AuthController {

    private final AuthService authService;
    private final OtpService otpService;
    private final AddressThrottle addressThrottle;
    private final ConsentService consents;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    @Operation(summary = "Register a patient account",
            description = "Both agreements must be given: the terms and privacy notice, and, separately, "
                    + "the use of health information. They are recorded with the account, in one transaction.")
    public AuthService.TokenPair register(@Valid @RequestBody RegisterRequest request) {
        addressThrottle.checkSignUp("patient");
        AuthService.TokenPair pair = authService.register(
                request.email(), request.password(), request.fullName(),
                request.phone(), request.dateOfBirth(), request.gender());
        consents.recordSignUp(UUID.fromString(pair.userId()), Role.PATIENT, request.acceptedHealthData());
        return pair;
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange credentials for a token pair")
    public AuthService.TokenPair login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    @PostMapping("/otp/email/send")
    @Operation(summary = "Email a sign-in code to the address an account was registered with",
            description = "The reply is the same whether or not the address has an account, and does not wait for the email to be sent.")
    public OtpService.CodeSent sendEmailCode(@Valid @RequestBody EmailRequest request) {
        return otpService.sendToEmail(request.email());
    }

    @PostMapping("/otp/email/verify")
    @Operation(summary = "Exchange an email address and the code sent to it for a token pair")
    public AuthService.TokenPair verifyEmailCode(@Valid @RequestBody EmailCodeRequest request) {
        return otpService.verifyEmail(request.email(), request.code());
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
    @Operation(summary = "End the session the refresh token belongs to",
            description = "If the request also carries the session's access token, that token is revoked at once.")
    public void logout(@Valid @RequestBody RefreshRequest request,
                       @RequestHeader(value = "Authorization", required = false) String authorization) {
        String accessToken = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()) : null;
        authService.logout(request.refreshToken(), accessToken);
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,

            // Length is the single strongest password control (the floor is a product choice: 5). Composition rules
            // ("must contain a symbol") push users toward predictable patterns
            // without adding real entropy, so they are deliberately omitted —
            // this follows NIST SP 800-63B rather than folklore.
            @NotBlank @Size(min = 5, max = 128,
                    message = "Password must be at least 5 characters") String password,

            @NotBlank @Size(max = 120) String fullName,

            @Pattern(regexp = "^$|^\\+?[0-9]{10,15}$",
                    message = "Phone must be 10-15 digits") String phone,

            @NotNull @Past(message = "Date of birth must be in the past") LocalDate dateOfBirth,

            Patient.Gender gender,

            // Two separate agreements (DPDP: consent specific to its purpose),
            // neither pre-ticked by the server. @AssertTrue gives the form a field error.
            @AssertTrue(message = "Please accept the Terms of Service and read the Privacy Notice") boolean acceptedTerms,
            @AssertTrue(message = "We need your agreement to keep your health information to run your account")
            boolean acceptedHealthData
    ) {}

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    public record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {}

    public record EmailCodeRequest(@NotBlank @Email @Size(max = 254) String email,
                                   @NotBlank @Size(max = 6) String code) {}
}
