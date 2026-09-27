package com.medicity.request;

import com.medicity.request.MedicineRequestService.Availability;
import com.medicity.request.MedicineRequestService.LineInput;
import com.medicity.request.MedicineRequestService.QueueEntry;
import com.medicity.request.MedicineRequestService.RequestSummary;
import com.medicity.request.MedicineRequestService.StoreView;
import com.medicity.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * "Ask all nearby chemists": the patient's questions, and each store's queue.
 * As elsewhere, no route takes a patient or store id; both come from the token.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Medicine requests")
public class MedicineRequestController {

    private final MedicineRequestService service;

    // --- patient -------------------------------------------------------------

    @PostMapping("/api/v1/patients/me/medicine-requests")
    @PreAuthorize("hasRole('PATIENT')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Send one of my prescriptions to every verified chemist within a radius")
    public Comparison ask(@AuthenticationPrincipal AppUserPrincipal principal, @Valid @RequestBody AskRequest request) {
        UUID id = service.ask(principal.getId(), request.prescriptionId(), request.latitude(), request.longitude(),
                request.radiusM(), request.medicineIds());
        return service.compare(principal.getId(), id);
    }

    @GetMapping("/api/v1/patients/me/medicine-requests")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "My questions to chemists, newest first")
    public List<RequestSummary> mine(@AuthenticationPrincipal AppUserPrincipal principal) {
        return service.mine(principal.getId());
    }

    @GetMapping("/api/v1/patients/me/medicine-requests/{id}")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "One question with every store's answer, best first")
    public Comparison compare(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable UUID id) {
        return service.compare(principal.getId(), id);
    }

    @PostMapping("/api/v1/patients/me/medicine-requests/{id}/close")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Withdraw a question; stores stop seeing it")
    public Comparison close(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable UUID id) {
        service.close(principal.getId(), id);
        return service.compare(principal.getId(), id);
    }

    // --- store ----------------------------------------------------------------

    @GetMapping("/api/v1/stores/me/requests")
    @PreAuthorize("hasRole('CHEMIST')")
    @Operation(summary = "Questions waiting for my store (default), or ones it answered")
    public List<QueueEntry> queue(@AuthenticationPrincipal AppUserPrincipal principal,
                                  @RequestParam(defaultValue = "pending") String show) {
        return service.queue(principal.getId(), "answered".equalsIgnoreCase(show.trim()));
    }

    @GetMapping("/api/v1/stores/me/requests/{id}")
    @PreAuthorize("hasRole('CHEMIST')")
    @Operation(summary = "A question sent to my store, with the prescription it came from")
    public StoreView forStore(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable UUID id) {
        return service.forStore(principal.getId(), id);
    }

    @PostMapping("/api/v1/stores/me/requests/{id}/answer")
    @PreAuthorize("hasRole('CHEMIST')")
    @Operation(summary = "Answer, per medicine: yes, partly (how many) or no, with the price")
    public StoreView answer(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable UUID id,
                            @Valid @RequestBody AnswerRequest request) {
        service.answer(principal.getId(), id, request.note(), request.lines().stream()
                .map(l -> new LineInput(l.medicineId(), l.availability(), l.quantity(), l.unitPrice(),
                        l.substituteMedicineId()))
                .toList());
        return service.forStore(principal.getId(), id);
    }

    // --- request shapes -------------------------------------------------------

    public record AskRequest(
            @NotNull UUID prescriptionId,
            @DecimalMin("-90") @DecimalMax("90") double latitude,
            @DecimalMin("-180") @DecimalMax("180") double longitude,
            @Min(100) @Max(10_000) int radiusM,
            /** Leave out to ask about every medicine on the prescription. */
            @Size(max = 20) List<@NotNull UUID> medicineIds
    ) {}

    public record AnswerRequest(
            @Size(max = 300) String note,
            @NotEmpty @Size(max = 20) List<@Valid AnswerLineRequest> lines
    ) {}

    public record AnswerLineRequest(
            @NotNull UUID medicineId,
            @NotNull Availability availability,
            /** How many the store has, for PARTIAL only. */
            @Min(1) @Max(1000) Integer quantity,
            /** Per unit (tablet, capsule, bottle). Required unless the answer is NO. */
            @DecimalMin("0.00") @DecimalMax("100000.00") @Digits(integer = 6, fraction = 2) BigDecimal unitPrice,
            /** Another brand of the same medicine, where the doctor allowed one. */
            UUID substituteMedicineId
    ) {}
}
