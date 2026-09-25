package com.medicity.doctor;

import com.medicity.scheduling.AppointmentSlot;
import com.medicity.scheduling.SlotRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public directory and availability lookup.
 *
 * <p>These are the only unauthenticated read endpoints in the system: a
 * prospective patient must be able to browse doctors and see open times before
 * creating an account. Nothing here exposes patient data — availability is
 * derived from slots, and a taken slot is simply absent from the response
 * rather than marked as taken with a name attached.
 */
@RestController
@RequestMapping("/api/v1/doctors")
@RequiredArgsConstructor
@Tag(name = "Doctors")
@SecurityRequirements
public class DoctorController {

    /** Widest window the availability endpoint will serve in one request. */
    private static final Duration MAX_WINDOW = Duration.ofDays(60);

    private final DoctorRepository doctorRepository;
    private final SlotRepository slotRepository;

    @GetMapping
    @Operation(summary = "Search the doctor directory")
    public Page<DoctorResponse> search(
            @RequestParam(required = false) String specialization,
            @RequestParam(required = false, name = "q") String nameQuery,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        // Page size is capped so a client cannot turn one request into a full
        // table scan by asking for size=1000000.
        var pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), 50),
                Sort.by("specialization"));

        return doctorRepository.search(blankToNull(specialization), blankToNull(nameQuery), pageable)
                .map(DoctorResponse::from);
    }

    /**
     * Open slots for a doctor in a time window.
     *
     * <p>This is a <em>snapshot</em>, not a reservation. Between this response
     * and the patient clicking "book", another patient may take any of these
     * slots. The booking endpoint is the only authority on availability; the
     * client is expected to handle a 409 rather than trust this list.
     */
    @GetMapping("/{doctorId}/slots")
    @Operation(summary = "List open slots for a doctor")
    public List<SlotResponse> slots(
            @PathVariable UUID doctorId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        Instant start = from.isBefore(Instant.now()) ? Instant.now() : from;
        // Clamping rather than rejecting: an over-wide window is almost always a
        // careless client, and returning 60 days of data is more useful than a 400.
        Instant end = to.isAfter(start.plus(MAX_WINDOW)) ? start.plus(MAX_WINDOW) : to;

        return slotRepository.findAvailable(doctorId, start, end).stream()
                .map(SlotResponse::from)
                .toList();
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    public record DoctorResponse(
            UUID id,
            String fullName,
            String specialization,
            BigDecimal consultationFee,
            int yearsExperience,
            String bio
    ) {
        static DoctorResponse from(Doctor d) {
            return new DoctorResponse(
                    d.getId(),
                    d.getUser().getFullName(),
                    d.getSpecialization(),
                    d.getConsultationFee(),
                    d.getYearsExperience(),
                    d.getBio());
        }
    }

    public record SlotResponse(UUID id, Instant startsAt, Instant endsAt) {
        static SlotResponse from(AppointmentSlot s) {
            return new SlotResponse(s.getId(), s.getStartsAt(), s.getEndsAt());
        }
    }
}
