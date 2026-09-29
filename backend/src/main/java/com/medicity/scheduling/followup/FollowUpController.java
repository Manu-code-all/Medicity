package com.medicity.scheduling.followup;

import com.medicity.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/appointments/{id}/followups")
@RequiredArgsConstructor
@Tag(name = "Follow-up questions")
public class FollowUpController {

    private final FollowUpService followUps;

    @GetMapping
    @PreAuthorize("hasAnyRole('PATIENT','DOCTOR')")
    @Operation(summary = "The follow-up thread, questions left and when the free period ends")
    public FollowUpService.Thread thread(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable UUID id) {
        return followUps.thread(id, principal);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('PATIENT','DOCTOR')")
    @Operation(summary = "Ask a follow-up question (patient) or answer one (the visit's doctor)")
    public FollowUpService.Thread post(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable UUID id,
                                       @Valid @RequestBody MessageRequest request) {
        return followUps.post(id, principal, request.body());
    }

    public record MessageRequest(@NotBlank @Size(max = 1000) String body) {}
}
