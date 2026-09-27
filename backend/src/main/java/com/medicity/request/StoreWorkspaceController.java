package com.medicity.request;

import com.medicity.security.AppUserPrincipal;
import com.medicity.store.StoreStockService;
import com.medicity.store.StoreStockService.StockInput;
import com.medicity.store.StoreStockService.StockView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** A chemist's demand insights and optional live stock. "Me" comes from the token. */
@RestController
@RequestMapping("/api/v1/stores/me")
@PreAuthorize("hasRole('CHEMIST')")
@RequiredArgsConstructor
@Tag(name = "Store workspace")
public class StoreWorkspaceController {

    private final StoreInsightsService insights;
    private final StoreStockService stock;

    @GetMapping("/insights")
    @Operation(summary = "What patients near my store asked for this week, and what I answered")
    public StoreInsightsService.Insights insights(@AuthenticationPrincipal AppUserPrincipal principal) {
        return insights.forStore(principal.getId());
    }

    @GetMapping("/stock")
    @Operation(summary = "My live stock, if I send it")
    public StockView stock(@AuthenticationPrincipal AppUserPrincipal principal) {
        return stock.stock(principal.getId());
    }

    @PutMapping("/stock")
    @Operation(summary = "Replace my whole stock list (for billing software, or typed in)",
            description = "A medicine missing from the list is out of stock. Stock older than a day does not "
                    + "answer questions automatically.")
    public StockView replaceStock(@AuthenticationPrincipal AppUserPrincipal principal,
                                  @Valid @RequestBody StockRequest request) {
        return stock.replace(principal.getId(), request.items().stream()
                .map(i -> new StockInput(i.medicineId(), i.quantity(), i.unitPrice())).toList());
    }

    @PutMapping("/auto-answer")
    @Operation(summary = "Answer questions automatically from my stock while it is fresh")
    public StockView autoAnswer(@AuthenticationPrincipal AppUserPrincipal principal,
                                @Valid @RequestBody AutoAnswerRequest request) {
        return stock.setAutoAnswer(principal.getId(), request.enabled());
    }

    public record StockRequest(@NotNull @Size(max = 5000) List<@Valid StockItem> items) {}

    public record StockItem(
            @NotNull UUID medicineId,
            @Min(0) @Max(1_000_000) int quantity,
            @NotNull @DecimalMin("0.00") @DecimalMax("100000.00") @Digits(integer = 6, fraction = 2) BigDecimal unitPrice
    ) {}

    public record AutoAnswerRequest(boolean enabled) {}
}
