package com.medicity.queue;

import com.medicity.common.NotFoundException;
import com.medicity.doctor.DoctorRepository;
import com.medicity.patient.ActingPatient;
import com.medicity.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Walk-in queue")
public class QueueController {

    private final QueueService queue;
    private final ActingPatient acting;
    private final DoctorRepository doctorRepository;

    // --- patients -----------------------------------------------------------

    /** Public, like the directory: whether walk-ins are open and how long the wait is. */
    @GetMapping("/doctors/{doctorId}/queue")
    @SecurityRequirements
    @Operation(summary = "Today's walk-in queue for a doctor: open or not, waiting, estimated wait")
    public QueueService.Status status(@PathVariable UUID doctorId) {
        return queue.status(doctorId);
    }

    @PostMapping("/doctors/{doctorId}/queue")
    @PreAuthorize("hasRole('PATIENT')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Take a walk-in token for today (for yourself or a family member)")
    public QueueService.Token join(@AuthenticationPrincipal AppUserPrincipal principal,
                                   @PathVariable UUID doctorId,
                                   @Valid @RequestBody JoinRequest request) {
        return queue.join(doctorId, acting.resolve(principal.getId()), request.reason());
    }

    @GetMapping("/queue/mine")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Today's tokens for you and your family, with places and waits")
    public List<QueueService.Token> mine(@AuthenticationPrincipal AppUserPrincipal principal) {
        return queue.mine(principal.getId());
    }

    @PostMapping("/queue/tokens/{id}/leave")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Give up a place in the queue")
    public QueueService.Token leave(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable UUID id) {
        return queue.leave(id, principal.getId());
    }

    // --- the doctor's front desk ------------------------------------------------

    @GetMapping("/doctors/me/queue")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "Front desk: today's walk-in line and whether it is open")
    public Desk desk(@AuthenticationPrincipal AppUserPrincipal principal) {
        UUID doctorId = doctorId(principal);
        return new Desk(queue.status(doctorId), queue.desk(doctorId));
    }

    @PostMapping("/doctors/me/queue/next")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "Call the next waiting token")
    public QueueService.Token next(@AuthenticationPrincipal AppUserPrincipal principal) {
        return queue.callNext(doctorId(principal));
    }

    @PostMapping("/doctors/me/queue/tokens/{id}/seen")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "Close a token as seen")
    public QueueService.Token seen(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable UUID id) {
        return queue.finish(doctorId(principal), id, true);
    }

    @PostMapping("/doctors/me/queue/tokens/{id}/missed")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "Close a token as missed (called, did not come in)")
    public QueueService.Token missed(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable UUID id) {
        return queue.finish(doctorId(principal), id, false);
    }

    @PostMapping("/doctors/me/queue/{state}")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "Open or close today's queue to new walk-ins (state: open | close)")
    public QueueService.Status openOrClose(@AuthenticationPrincipal AppUserPrincipal principal,
                                           @PathVariable String state) {
        if (!state.equals("open") && !state.equals("close")) {
            throw new NotFoundException("Queue action", state);
        }
        return queue.setOpen(doctorId(principal), state.equals("open"));
    }

    private UUID doctorId(AppUserPrincipal principal) {
        return doctorRepository.findByUserId(principal.getId())
                .orElseThrow(() -> new NotFoundException("Doctor profile for user", principal.getId()))
                .getId();
    }

    public record JoinRequest(@Size(max = 300) String reason) {}

    public record Desk(QueueService.Status status, List<QueueService.Token> tokens) {}
}
