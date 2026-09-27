package com.medicity.store;

import com.medicity.audit.AuditLog;
import com.medicity.common.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A store's optional live stock, and whether questions are answered from it.
 *
 * <p>The list is replaced whole on every upload, never patched line by line.
 * Billing software knows its current stock, not what changed since Medicity
 * last heard, and a whole list gives one freshness time for everything in it:
 * a medicine missing from the latest upload is out of stock, not "unknown".
 */
@Service
@RequiredArgsConstructor
public class StoreStockService {

    /** Stock older than this does not answer questions. */
    public static final Duration FRESH_FOR = Duration.ofHours(24);
    static final int MAX_LINES = 5_000;

    private final JdbcTemplate jdbc;
    private final StoreService storeService;
    private final AuditLog auditLog;
    private final Clock clock;

    @Transactional(readOnly = true)
    public StockView stock(UUID ownerUserId) {
        Store store = storeService.requireOwn(ownerUserId);
        List<StockLine> lines = jdbc.query("""
                SELECT st.medicine_id, m.name, m.strength, m.form, st.quantity, st.unit_price
                FROM store_stock st JOIN medicines m ON m.id = st.medicine_id
                WHERE st.store_id = ?
                ORDER BY m.name, m.strength
                """, (rs, i) -> new StockLine(rs.getObject("medicine_id", UUID.class), rs.getString("name"),
                rs.getString("strength"), rs.getString("form"), rs.getInt("quantity"),
                rs.getBigDecimal("unit_price")), store.getId());
        Instant updated = jdbc.queryForObject("SELECT stock_updated_at FROM stores WHERE id = ?",
                (rs, i) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), store.getId());
        boolean auto = Boolean.TRUE.equals(jdbc.queryForObject("SELECT auto_answer FROM stores WHERE id = ?",
                Boolean.class, store.getId()));
        return new StockView(auto, updated, updated != null && isFresh(updated), lines);
    }

    /** Replaces the whole stock list. What is not in it is out of stock. */
    @Transactional
    public StockView replace(UUID ownerUserId, List<StockInput> items) {
        Store store = storeService.requireOwn(ownerUserId);
        if (items.size() > MAX_LINES) {
            throw new ValidationException("TOO_MANY_LINES", "Send at most %d medicines at once".formatted(MAX_LINES));
        }
        Set<UUID> ids = new HashSet<>();
        for (StockInput item : items) {
            if (!ids.add(item.medicineId())) {
                throw new ValidationException("DUPLICATE_MEDICINE", "Each medicine may appear once");
            }
        }
        if (!ids.isEmpty()) {
            Set<UUID> known = new HashSet<>(jdbc.query(
                    "SELECT id FROM medicines WHERE active AND id = ANY (?)",
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids.toArray())),
                    (rs, i) -> rs.getObject("id", UUID.class)));
            ids.removeAll(known);
            if (!ids.isEmpty()) {
                throw new ValidationException("UNKNOWN_MEDICINE",
                        "Not in the catalogue: " + ids.stream().map(UUID::toString).collect(Collectors.joining(", ")));
            }
        }

        jdbc.update("DELETE FROM store_stock WHERE store_id = ?", store.getId());
        jdbc.batchUpdate("INSERT INTO store_stock (store_id, medicine_id, quantity, unit_price) VALUES (?, ?, ?, ?)",
                items.stream().map(i -> new Object[]{store.getId(), i.medicineId(), i.quantity(), i.unitPrice()})
                        .toList());
        jdbc.update("UPDATE stores SET stock_updated_at = ? WHERE id = ?",
                Timestamp.from(clock.instant()), store.getId());
        auditLog.recordChange("STORE_STOCK_REPLACED", "STORE", store.getId(), Map.of("lines", items.size()));
        return stock(ownerUserId);
    }

    @Transactional
    public StockView setAutoAnswer(UUID ownerUserId, boolean enabled) {
        Store store = storeService.requireOwn(ownerUserId);
        jdbc.update("UPDATE stores SET auto_answer = ? WHERE id = ?", enabled, store.getId());
        auditLog.recordChange("STORE_AUTO_ANSWER", "STORE", store.getId(), Map.of("enabled", enabled));
        return stock(ownerUserId);
    }

    /**
     * The stock a question may be answered from, if the store asked for
     * automatic answers and its stock is fresh; empty otherwise.
     */
    @Transactional(readOnly = true)
    public Optional<Map<UUID, StockLine>> answeringStock(UUID storeId) {
        Boolean usable = jdbc.queryForObject("""
                SELECT auto_answer AND stock_updated_at IS NOT NULL AND stock_updated_at > ?
                FROM stores WHERE id = ?
                """, Boolean.class, Timestamp.from(clock.instant().minus(FRESH_FOR)), storeId);
        if (!Boolean.TRUE.equals(usable)) {
            return Optional.empty();
        }
        return Optional.of(jdbc.query("""
                SELECT st.medicine_id, m.name, m.strength, m.form, st.quantity, st.unit_price
                FROM store_stock st JOIN medicines m ON m.id = st.medicine_id
                WHERE st.store_id = ?
                """, (rs, i) -> new StockLine(rs.getObject("medicine_id", UUID.class), rs.getString("name"),
                rs.getString("strength"), rs.getString("form"), rs.getInt("quantity"),
                rs.getBigDecimal("unit_price")), storeId).stream()
                .collect(Collectors.toMap(StockLine::medicineId, l -> l)));
    }

    private boolean isFresh(Instant updated) {
        return updated.isAfter(clock.instant().minus(FRESH_FOR));
    }

    public record StockInput(UUID medicineId, int quantity, BigDecimal unitPrice) {}

    public record StockLine(UUID medicineId, String name, String strength, String form, int quantity,
                            BigDecimal unitPrice) {}

    public record StockView(boolean autoAnswer, Instant updatedAt, boolean fresh, List<StockLine> items) {}
}
