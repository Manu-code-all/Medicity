package com.medicity.scheduling.waitlist;

import com.medicity.patient.ActingPatient;
import com.medicity.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Waiting list")
public class WaitlistController {

    private final WaitlistService waitlist;
    private final ActingPatient acting;

    @PostMapping("/doctors/{doctorId}/waitlist")
    @PreAuthorize("hasRole('PATIENT')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Be told if a time opens with this doctor on this day (for yourself or a family member)")
    public WaitlistService.Entry join(@AuthenticationPrincipal AppUserPrincipal principal,
                                      @PathVariable UUID doctorId,
                                      @Valid @RequestBody JoinRequest request) {
        return waitlist.join(doctorId, acting.resolve(principal.getId()), request.date());
    }

    @DeleteMapping("/doctors/{doctorId}/waitlist")
    @PreAuthorize("hasRole('PATIENT')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Stop waiting for a time with this doctor on this day")
    public void leave(@AuthenticationPrincipal AppUserPrincipal principal,
                      @PathVariable UUID doctorId,
                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        waitlist.leave(doctorId, acting.resolve(principal.getId()).getId(), date);
    }

    @GetMapping("/patients/me/waitlist")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "The days you and your family are waiting for, from today on")
    public List<WaitlistService.Entry> mine(@AuthenticationPrincipal AppUserPrincipal principal) {
        return waitlist.mine(principal.getId());
    }

    public record JoinRequest(@NotNull LocalDate date) {}
}
