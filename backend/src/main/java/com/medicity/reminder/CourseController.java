package com.medicity.reminder;

import com.medicity.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "Patient portal")
public class CourseController {

    private final CourseService courses;

    @GetMapping("/api/v1/patients/me/courses")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Each prescribed medicine: when it started, when it runs out, running out first")
    public List<CourseService.Course> mine(@AuthenticationPrincipal AppUserPrincipal principal) {
        return courses.forPatient(principal.getId());
    }
}
