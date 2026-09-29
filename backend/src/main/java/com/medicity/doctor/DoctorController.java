package com.medicity.doctor;

import com.medicity.review.ReviewService;
import com.medicity.scheduling.AppointmentSlot;
import com.medicity.scheduling.BookingService;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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

    /** How many next times each directory card offers, and how far ahead it looks for them. */
    private static final int NEXT_SLOTS = 3;
    private static final Duration NEXT_SLOTS_WINDOW = Duration.ofDays(14);

    private final DoctorRepository doctorRepository;
    private final SlotRepository slotRepository;
    private final ReviewService reviewService;
    private final DoctorOffers offers;

    /** Specialisations for the filter and the quick chips: only ones someone can be booked in. */
    @GetMapping("/specialties")
    @Operation(summary = "Specialisations with at least one doctor, and how many")
    public List<SpecialtyResponse> specialties() {
        return doctorRepository.specialties(null).stream().map(SpecialtyResponse::from).toList();
    }

    /**
     * What the search box offers while someone types: matching specialisations
     * first, then up to five doctors by name or specialisation. Fewer than two
     * characters answers nothing, so a single keystroke does not list everyone.
     */
    @GetMapping("/suggest")
    @Operation(summary = "Suggestions for the doctor search box")
    public Suggestions suggest(@RequestParam("q") String query) {
        String q = blankToNull(query);
        if (q == null || q.length() < 2) {
            return new Suggestions(List.of(), List.of());
        }
        return new Suggestions(
                doctorRepository.specialties(q).stream().map(SpecialtyResponse::from).toList(),
                doctorRepository.search(null, q, PageRequest.of(0, 5, Sort.by("specialization")))
                        .map(d -> DoctorResponse.from(d, List.of(), null)).getContent());
    }

    /** The insurers the directory can filter by. */
    @GetMapping("/insurers")
    @Operation(summary = "Insurers and schemes a doctor can accept (for the directory filter)")
    public List<DoctorOffers.Insurer> insurers() {
        return offers.insurers();
    }

    @GetMapping
    @Operation(summary = "Search the doctor directory",
            description = "`q` matches a doctor's name or specialisation; `specialization` and `insurance` are exact filters.")
    public Page<DoctorResponse> search(
            @RequestParam(required = false) String specialization,
            @RequestParam(required = false, name = "q") String nameQuery,
            @RequestParam(required = false) String insurance,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        // Page size is capped so a client cannot turn one request into a full
        // table scan by asking for size=1000000.
        var pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), 50),
                Sort.by("specialization"));

        Page<Doctor> doctors = doctorRepository.search(blankToNull(specialization), blankToNull(nameQuery),
                blankToNull(insurance), pageable);
        List<UUID> ids = doctors.map(Doctor::getId).getContent();
        Map<UUID, List<SlotResponse>> next = nextSlots(ids);
        Map<UUID, ReviewService.Rating> ratings = reviewService.ratings(ids);
        Map<UUID, List<String>> insurers = offers.insurersOf(ids);
        Map<UUID, List<DoctorOffers.Price>> prices = offers.pricesOf(ids);
        return doctors.map(d -> DoctorResponse.from(d, next.getOrDefault(d.getId(), List.of()), ratings.get(d.getId()))
                .withOffers(insurers.getOrDefault(d.getId(), List.of()), prices.getOrDefault(d.getId(), List.of())));
    }

    /**
     * Each card's next few bookable times. Starts after the booking service's
     * minimum notice, so every time offered can actually be booked; like the
     * slot list, it is a snapshot and the booking itself is the authority.
     */
    private Map<UUID, List<SlotResponse>> nextSlots(List<UUID> doctorIds) {
        if (doctorIds.isEmpty()) {
            return Map.of();
        }
        Instant from = Instant.now().plus(BookingService.MIN_LEAD_TIME);
        List<UUID> ids = slotRepository.findNextAvailableIds(doctorIds, from, from.plus(NEXT_SLOTS_WINDOW), NEXT_SLOTS);
        return slotRepository.findAllById(ids).stream()
                .sorted(Comparator.comparing(AppointmentSlot::getStartsAt))
                .collect(Collectors.groupingBy(s -> s.getDoctor().getId(), LinkedHashMap::new,
                        Collectors.mapping(SlotResponse::from, Collectors.toList())));
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
            String bio,
            /** The next few open times, soonest first; empty in suggestions. */
            List<SlotResponse> nextSlots,
            /** Average of reviews from completed visits, to one decimal; null when there are none. */
            Double rating,
            int reviewCount,
            /** Insurers and schemes the clinic accepts. */
            List<String> insurers,
            /** Charges beyond the consultation fee; "every visit" ones are added to it. */
            List<DoctorOffers.Price> prices
    ) {
        DoctorResponse withOffers(List<String> insurers, List<DoctorOffers.Price> prices) {
            return new DoctorResponse(id, fullName, specialization, consultationFee, yearsExperience, bio, nextSlots,
                    rating, reviewCount, insurers, prices);
        }

        static DoctorResponse from(Doctor d, List<SlotResponse> nextSlots, ReviewService.Rating rating) {
            return new DoctorResponse(
                    d.getId(),
                    d.getUser().getFullName(),
                    d.getSpecialization(),
                    d.getConsultationFee(),
                    d.getYearsExperience(),
                    d.getBio(),
                    nextSlots,
                    rating == null ? null : rating.average(),
                    rating == null ? 0 : rating.count(),
                    List.of(),
                    List.of());
        }
    }

    public record SpecialtyResponse(String name, long doctors) {
        static SpecialtyResponse from(DoctorRepository.SpecialtyCount c) {
            return new SpecialtyResponse(c.getName(), c.getDoctors());
        }
    }

    public record Suggestions(List<SpecialtyResponse> specialties, List<DoctorResponse> doctors) {}

    public record SlotResponse(UUID id, Instant startsAt, Instant endsAt) {
        static SlotResponse from(AppointmentSlot s) {
            return new SlotResponse(s.getId(), s.getStartsAt(), s.getEndsAt());
        }
    }
}
