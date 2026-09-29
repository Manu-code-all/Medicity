package com.medicity.video;

import com.medicity.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/appointments")
@RequiredArgsConstructor
@Tag(name = "Video visits")
public class VideoController {

    private final VideoTickets tickets;

    /**
     * A one-time ticket to open {@code /ws/video?ticket=...} for this visit,
     * and the STUN servers the browser should use.
     */
    @PostMapping("/{id}/video-ticket")
    @PreAuthorize("hasAnyRole('PATIENT','DOCTOR')")
    @Operation(summary = "Join a video visit: a one-time ticket for the signalling socket")
    public VideoTickets.Ticket ticket(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable UUID id) {
        return tickets.issue(id, principal);
    }
}
