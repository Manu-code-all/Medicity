package com.medicity.doctor;

import com.medicity.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/** The doctor's own fee, bio, insurers and price list, as the directory shows them. */
@RestController
@RequestMapping("/api/v1/doctors/me/practice")
@PreAuthorize("hasRole('DOCTOR')")
@RequiredArgsConstructor
@Tag(name = "Doctor accounts")
public class DoctorPracticeController {

    private final DoctorPracticeService practice;

    @GetMapping
    @Operation(summary = "The caller's fee, bio, insurers and prices, with every insurer they could choose")
    public DoctorPracticeService.Practice get(@AuthenticationPrincipal AppUserPrincipal principal) {
        return practice.practice(principal.getId());
    }

    @PutMapping
    @Operation(summary = "Replace the caller's fee, bio, insurers and prices",
            description = "The directory shows the change at once. A price left out is withdrawn.")
    public DoctorPracticeService.Practice update(@AuthenticationPrincipal AppUserPrincipal principal,
                                                 @Valid @RequestBody PracticeRequest r) {
        return practice.update(principal.getId(), r.consultationFee(), r.bio(), r.yearsExperience(),
                r.insurers(), r.prices().stream()
                        .map(p -> new DoctorOffers.Price(p.procedure(), p.priceInr(), p.everyVisit())).toList());
    }

    public record PracticeRequest(
            @NotNull @DecimalMin("0") @DecimalMax("100000") BigDecimal consultationFee,
            @Size(max = 1000) String bio,
            @Min(0) @Max(70) int yearsExperience,
            @NotNull @Size(max = 40) List<@NotBlank String> insurers,
            @NotNull @Size(max = 40) List<@Valid PriceLine> prices
    ) {}

    public record PriceLine(
            @NotBlank @Size(max = 80) String procedure,
            @Min(0) @Max(500000) int priceInr,
            boolean everyVisit
    ) {}
}
