package com.medicity.scheduling;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The body guide's answers, attached to a booking when the patient chooses
 * (V27): which area, the symptoms ticked, since when, and the kind of doctor
 * the guide suggested. A snapshot in the patient's terms, shown to the doctor
 * before the visit; it is never used to decide anything.
 */
public record Intake(
        @NotBlank @Size(max = 60) String area,
        @Size(max = 10) List<@NotBlank @Size(max = 120) String> symptoms,
        @Size(max = 40) String since,
        @Size(max = 60) String suggested
) {
    public Intake {
        symptoms = symptoms == null ? List.of() : List.copyOf(symptoms);
    }
}
