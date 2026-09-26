package com.medicity.pharmacy;

import com.medicity.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pharmacy desk. The catalogue is readable by anyone signed in; changing stock
 * is an administrator's job until a dedicated pharmacist role exists.
 */
@RestController
@RequestMapping("/api/v1/pharmacy")
@RequiredArgsConstructor
@Tag(name = "Pharmacy")
public class PharmacyController {

    private final MedicineRepository medicineRepository;
    private final MedicineStockRepository stockRepository;
    private final StockLedger stockLedger;
    private final DispensingService dispensingService;

    @GetMapping("/medicines")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Search the medicine catalogue")
    public Page<MedicineResponse> medicines(@RequestParam(required = false, name = "q") String query,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        String q = query == null || query.isBlank() ? null : query.trim();
        Page<Medicine> result = medicineRepository.search(q,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));

        // One query for the whole page's stock, not one per medicine.
        Map<UUID, MedicineStock> stock = stockRepository
                .findAllById(result.map(Medicine::getId).toList()).stream()
                .collect(Collectors.toMap(MedicineStock::getMedicineId, Function.identity()));

        return result.map(m -> MedicineResponse.from(m, stock.get(m.getId())));
    }

    @GetMapping("/stock/low")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Medicines at or below their reorder level")
    public List<LowStockResponse> lowStock() {
        return stockRepository.findAtOrBelowReorderLevel().stream()
                .map(l -> new LowStockResponse(l.getMedicineId(), l.getName(), l.getStrength(),
                        l.getQuantityOnHand(), l.getReorderLevel()))
                .toList();
    }

    @PostMapping("/medicines/{medicineId}/restock")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Add received stock")
    public MedicineResponse restock(@PathVariable UUID medicineId, @Valid @RequestBody RestockRequest request) {
        stockLedger.restock(medicineId, request.quantity(), request.purchaseOrderId());
        Medicine medicine = medicineRepository.findById(medicineId).orElseThrow();
        return MedicineResponse.from(medicine, stockRepository.findById(medicineId).orElse(null));
    }

    @PostMapping("/prescriptions/{prescriptionId}/dispense")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Dispense every medicine on a prescription, or none")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Dispensed"),
            @ApiResponse(responseCode = "409", description = "Already dispensed, or a medicine is out of stock"),
            @ApiResponse(responseCode = "422", description = "Prescription was superseded by a correction")
    })
    public DispensingService.DispenseResult dispense(@AuthenticationPrincipal AppUserPrincipal principal,
                                                     @PathVariable UUID prescriptionId) {
        return dispensingService.dispense(prescriptionId, principal.getId());
    }

    public record RestockRequest(
            @Min(1) @Max(100_000) int quantity,
            UUID purchaseOrderId
    ) {}

    public record MedicineResponse(
            UUID id,
            String name,
            String genericName,
            String form,
            String strength,
            BigDecimal unitPrice,
            boolean prescriptionRequired,
            int quantityOnHand,
            boolean lowStock
    ) {
        static MedicineResponse from(Medicine m, MedicineStock s) {
            int onHand = s == null ? 0 : s.getQuantityOnHand();
            boolean low = s == null || onHand <= s.getReorderLevel();
            return new MedicineResponse(m.getId(), m.getName(), m.getGenericName(), m.getForm().name(),
                    m.getStrength(), m.getUnitPrice(), m.isPrescriptionRequired(), onHand, low);
        }
    }

    public record LowStockResponse(UUID medicineId, String name, String strength, int quantityOnHand, int reorderLevel) {}
}
