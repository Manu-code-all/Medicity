package com.medicity.pharmacy;

import com.medicity.common.ConflictException;
import com.medicity.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves pharmacy stock cannot be oversold under concurrent dispensing.
 *
 * <p>This is the same hazard as {@code SlotBookingConcurrencyTest}, solved a
 * different way. Booking contends over an identity, so a unique index arbitrates.
 * Stock contends over a count, which uniqueness cannot express — so the guard is
 * a conditional UPDATE plus a CHECK constraint instead.
 *
 * <p>The decisive assertion is not the success count but the final balance: a
 * system can report the right number of successes and still have corrupted the
 * quantity if the arithmetic raced.
 */
@DisplayName("Concurrent stock dispensing")
class StockLedgerConcurrencyTest extends AbstractIntegrationTest {

    @Autowired StockLedger stockLedger;
    @Autowired MedicineRepository medicineRepository;
    @Autowired MedicineStockRepository stockRepository;
    @Autowired StockMovementRepository movementRepository;

    private UUID medicineId;

    private static final int INITIAL_STOCK = 10;
    private static final int UNITS_PER_DISPENSE = 1;
    private static final int CONTENDERS = 40;

    @BeforeEach
    void setUp() {
        movementRepository.deleteAll();
        stockRepository.deleteAll();
        medicineRepository.deleteAll();

        Medicine paracetamol = medicineRepository.save(Medicine.builder()
                .name("Paracetamol")
                .genericName("Acetaminophen")
                .manufacturer("Generic Pharma")
                .form(Medicine.Form.TABLET)
                .strength("500mg")
                .unitPrice(new BigDecimal("40.00"))
                .prescriptionRequired(false)
                .active(true)
                .build());

        medicineId = paracetamol.getId();

        stockRepository.save(MedicineStock.builder()
                .medicineId(medicineId)
                .quantityOnHand(INITIAL_STOCK)
                .reorderLevel(5)
                .build());
    }

    @Test
    @DisplayName("40 dispensers against 10 units: exactly 10 succeed, stock lands on 0")
    void stockIsNeverOversold() throws Exception {
        CountDownLatch ready = new CountDownLatch(CONTENDERS);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger dispensed = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(CONTENDERS);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < CONTENDERS; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        stockLedger.dispense(medicineId, UNITS_PER_DISPENSE, UUID.randomUUID());
                        dispensed.incrementAndGet();
                    } catch (ConflictException expected) {
                        refused.incrementAndGet();
                    } catch (Throwable t) {
                        unexpected.add(t);
                    }
                }));
            }

            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(unexpected)
                .as("a contended dispense should only ever fail with ConflictException")
                .isEmpty();

        assertThat(dispensed.get())
                .as("exactly as many dispenses as there were units")
                .isEqualTo(INITIAL_STOCK);

        assertThat(refused.get()).isEqualTo(CONTENDERS - INITIAL_STOCK);

        // The claim that actually matters. If the decrements had raced, this
        // would be some number above zero while the success count still looked
        // plausible — the signature of a lost update.
        assertThat(stockRepository.findById(medicineId))
                .get()
                .satisfies(stock -> assertThat(stock.getQuantityOnHand()).isZero());

        // The ledger must account for every unit that left the shelf.
        assertThat(movementRepository.findByMedicineIdOrderByOccurredAtDesc(medicineId))
                .hasSize(INITIAL_STOCK)
                .allSatisfy(m -> assertThat(m.getDelta()).isEqualTo(-UNITS_PER_DISPENSE));
    }

    @Test
    @DisplayName("a dispense larger than remaining stock is refused outright")
    void oversizedDispenseIsRefused() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> stockLedger.dispense(medicineId, INITIAL_STOCK + 1, UUID.randomUUID()))
                .isInstanceOf(ConflictException.class);

        // A refused dispense must leave both the balance and the ledger untouched.
        assertThat(stockRepository.findById(medicineId))
                .get()
                .satisfies(s -> assertThat(s.getQuantityOnHand()).isEqualTo(INITIAL_STOCK));
        assertThat(movementRepository.findByMedicineIdOrderByOccurredAtDesc(medicineId)).isEmpty();
    }

    @Test
    @DisplayName("a negative quantity cannot be smuggled in as a dispense")
    void negativeDispenseIsRejected() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> stockLedger.dispense(medicineId, -5, UUID.randomUUID()))
                .isInstanceOf(com.medicity.common.ValidationException.class);

        assertThat(stockRepository.findById(medicineId))
                .get()
                .satisfies(s -> assertThat(s.getQuantityOnHand()).isEqualTo(INITIAL_STOCK));
    }
}
