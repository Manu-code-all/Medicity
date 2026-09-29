package com.medicity.clinical;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Diagnosis codes for the prescription writer's search box. */
@RestController
@RequestMapping("/api/v1/diagnoses")
@RequiredArgsConstructor
@Tag(name = "Doctor workspace")
public class DiagnosisController {

    private final DiagnosisCodes codes;

    @GetMapping
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "ICD-10 codes matching a code, a title or an everyday word (up to eight)")
    public List<DiagnosisCodes.Code> search(@RequestParam("q") String query) {
        return codes.search(query);
    }
}
