package com.medicity.review;

import com.medicity.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
@Tag(name = "Reviews")
public class ReviewController {

    private final ReviewService reviews;

    @PostMapping("/appointments/{id}/review")
    @PreAuthorize("hasRole('PATIENT')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Review a completed visit (once)")
    public void review(@AuthenticationPrincipal AppUserPrincipal principal,
                       @PathVariable UUID id,
                       @Valid @RequestBody ReviewRequest request) {
        reviews.submit(id, principal.getId(), request.rating(), request.comment());
    }

    /** Public, like the directory it belongs to. */
    @GetMapping("/doctors/{doctorId}/reviews")
    @SecurityRequirements
    @Operation(summary = "A doctor's latest reviews, newest first")
    public List<ReviewService.Review> latest(@PathVariable UUID doctorId) {
        return reviews.latest(doctorId, 10);
    }

    public record ReviewRequest(@Min(1) @Max(5) int rating, @Size(max = 1000) String comment) {}
}
