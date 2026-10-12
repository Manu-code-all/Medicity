package com.medicity.pharmacy;

import com.medicity.audit.AuditLog;
import com.medicity.privacy.ConsentService;
import com.medicity.security.AuthService;
import com.medicity.user.Role;
import com.medicity.user.User;
import com.medicity.user.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An administrator creates the hospital pharmacy's sign-ins. There is no
 * self-service sign-up for a pharmacist: the role can dispense and restock.
 */
@RestController
@RequestMapping("/api/v1/admin/pharmacists")
@RequiredArgsConstructor
@Tag(name = "Pharmacy")
@PreAuthorize("hasRole('ADMIN')")
public class PharmacistAdminController {

    private final AuthService authService;
    private final UserRepository users;
    private final ConsentService consents;
    private final AuditLog auditLog;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    @Operation(summary = "Create a pharmacist account",
            description = "The temporary password is given to the pharmacist out of band. "
                    + "It is not possible yet to force a change at first sign-in.")
    public PharmacistResponse create(@Valid @RequestBody CreatePharmacist request) {
        User user = authService.createAccount(request.email(), request.temporaryPassword(),
                request.fullName(), null, Role.PHARMACIST);
        // Same transaction as the account; staff agree to nothing here (see ConsentService.requiredFor).
        consents.recordSignUp(user.getId(), Role.PHARMACIST, false);
        auditLog.recordChange("PHARMACIST_CREATED", "USER", user.getId(), Map.of("email", user.getEmail()));
        return PharmacistResponse.from(user);
    }

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "The pharmacist accounts, by name")
    public List<PharmacistResponse> list() {
        return users.findByRoleOrderByFullNameAsc(Role.PHARMACIST).stream().map(PharmacistResponse::from).toList();
    }

    public record CreatePharmacist(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 120) String fullName,
            // Longer than the patient floor: this account can change stock.
            @NotBlank @Size(min = 10, max = 128, message = "Password must be at least 10 characters")
            String temporaryPassword
    ) {}

    public record PharmacistResponse(UUID id, String email, String fullName, boolean enabled, Instant createdAt) {
        static PharmacistResponse from(User u) {
            return new PharmacistResponse(u.getId(), u.getEmail(), u.getFullName(), u.isEnabled(), u.getCreatedAt());
        }
    }
}
