package com.medicity.request;

import com.medicity.request.MedicineRequestService.AnswerLine;
import com.medicity.request.MedicineRequestService.Availability;
import com.medicity.request.MedicineRequestService.Item;
import com.medicity.request.MedicineRequestService.RequestHeader;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Every store's answer to one question, side by side, best first.
 *
 * <p>"Best" is, in order: has everything; has more of the medicines; costs
 * less for what it has; is nearer. Stores that have not answered come last,
 * nearest first. The ranking is done here rather than in the browser so every
 * client shows the same order, and it is covered by a test.
 */
public record Comparison(
        UUID id,
        String status,
        Instant createdAt,
        Instant expiresAt,
        UUID prescriptionId,
        String doctorName,
        String diagnosis,
        int storesAsked,
        int radiusM,
        List<Item> items,
        List<StoreAnswer> stores
) {

    Comparison(RequestHeader h, List<Item> items, List<StoreAnswer> ranked) {
        this(h.id(), h.status(), h.createdAt(), h.expiresAt(), h.prescriptionId(), h.doctorName(), h.diagnosis(),
                h.storesAsked(), h.radiusM(), items, ranked);
    }

    static final Comparator<StoreAnswer> BEST_FIRST = Comparator
            .comparing((StoreAnswer s) -> !s.answered())
            .thenComparing(s -> !s.complete())
            .thenComparing(StoreAnswer::medicinesAvailable, Comparator.reverseOrder())
            .thenComparing(s -> s.total() == null ? BigDecimal.ZERO : s.total())
            .thenComparingInt(StoreAnswer::distanceM);

    static List<StoreAnswer> rank(List<StoreAnswer> answers) {
        List<StoreAnswer> ranked = answers.stream().sorted(BEST_FIRST).toList();
        // Labels for the patient's eye: the cheapest store with everything, and
        // the nearest store with everything.
        UUID cheapest = ranked.stream().filter(StoreAnswer::complete)
                .min(Comparator.comparing(StoreAnswer::total).thenComparingInt(StoreAnswer::distanceM))
                .map(StoreAnswer::storeId).orElse(null);
        UUID nearest = ranked.stream().filter(StoreAnswer::complete)
                .min(Comparator.comparingInt(StoreAnswer::distanceM))
                .map(StoreAnswer::storeId).orElse(null);
        return ranked.stream().map(s -> s.labelled(s.storeId().equals(cheapest), s.storeId().equals(nearest)))
                .toList();
    }

    public record StoreAnswer(
            UUID storeId,
            String name,
            String addressLine,
            String phone,
            int distanceM,
            boolean openNow,
            int holdHours,
            boolean answered,
            String note,
            Instant answeredAt,
            List<AnswerLine> lines,
            /** Medicines this store has at least some of. */
            int medicinesAvailable,
            /** Has every medicine in the full quantity (another allowed brand counts). */
            boolean complete,
            /** What the store has, at its prices; null before it answers or if it has nothing. */
            BigDecimal total,
            boolean cheapestComplete,
            boolean nearestComplete
    ) {
        static StoreAnswer of(UUID storeId, String name, String addressLine, String phone, int distanceM,
                              boolean openNow, int holdHours, boolean answered, String note, Instant answeredAt,
                              List<AnswerLine> lines, int itemsAsked) {
            int available = (int) lines.stream().filter(l -> l.availability() != Availability.NO).count();
            boolean complete = answered && lines.size() == itemsAsked
                    && lines.stream().allMatch(l -> l.availability() == Availability.YES);
            BigDecimal total = available == 0 ? null : lines.stream()
                    .filter(l -> l.availability() != Availability.NO)
                    .map(l -> l.unitPrice().multiply(BigDecimal.valueOf(l.quantityAvailable())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return new StoreAnswer(storeId, name, addressLine, phone, distanceM, openNow, holdHours, answered, note,
                    answeredAt, lines, available, complete, total, false, false);
        }

        StoreAnswer labelled(boolean cheapest, boolean nearest) {
            return new StoreAnswer(storeId, name, addressLine, phone, distanceM, openNow, holdHours, answered, note,
                    answeredAt, lines, medicinesAvailable, complete, total, cheapest, nearest);
        }
    }
}
