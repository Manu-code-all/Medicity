package com.medicity.pharmacy;

import com.medicity.clinical.Prescription;
import com.medicity.clinical.PrescriptionItem;
import com.medicity.clinical.PrescriptionRepository;
import com.medicity.common.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * How the pharmacy counter finds a prescription to fill.
 *
 * <p>The patient reads out the prescription's <i>reference</i> (the first eight
 * characters of its id, shown on their prescription), or the counter searches by
 * the patient's name. A corrected prescription's original is never returned: the
 * correction replaces it. The six-digit pickup code belongs to a neighbourhood
 * chemist's reservation, a different system, and is deliberately not accepted here.
 */
@Service
@RequiredArgsConstructor
public class PrescriptionLookup {

    static final int MAX_RESULTS = 10;

    private final JdbcTemplate jdbc;
    private final PrescriptionRepository prescriptions;
    private final DispensationRepository dispensations;
    private final MedicineStockRepository stock;

    public record Line(UUID medicineId, String medicine, String strength, int quantity, String dosage,
                       String frequency, int durationDays, int quantityOnHand) {}

    public record Found(UUID id, String reference, String patientName, String doctorName, String diagnosis,
                        Instant issuedAt, Instant dispensedAt, boolean canDispense, List<Line> items) {}

    @Transactional(readOnly = true)
    public List<Found> find(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.length() < 3) {
            throw new ValidationException("QUERY_TOO_SHORT",
                    "Type at least 3 characters of the reference or the patient's name");
        }
        // The reference is hex; anything else is a name. '%' and '_' are literal in both.
        String escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        boolean hex = q.matches("[0-9a-f-]{3,36}");
        List<UUID> ids = jdbc.query("""
                SELECT p.id FROM prescriptions p
                JOIN patients pt ON pt.id = p.patient_id
                LEFT JOIN users u ON u.id = pt.user_id
                WHERE NOT EXISTS (SELECT 1 FROM prescriptions newer WHERE newer.supersedes_id = p.id)
                  AND ((? AND p.id::text LIKE ? || '%')
                       OR lower(COALESCE(pt.full_name, u.full_name)) LIKE '%' || ? || '%')
                ORDER BY p.issued_at DESC
                LIMIT ?
                """, (rs, i) -> rs.getObject("id", UUID.class), hex, escaped, escaped, MAX_RESULTS);

        Map<UUID, Instant> done = dispensations.findByPrescriptionIdIn(ids).stream()
                .collect(Collectors.toMap(Dispensation::getPrescriptionId, Dispensation::getDispensedAt));
        return ids.stream().map(id -> toFound(id, done.get(id))).toList();
    }

    private Found toFound(UUID id, Instant dispensedAt) {
        Prescription p = prescriptions.findWithItems(id).orElseThrow();
        Map<UUID, Integer> onHand = stock.findAllById(p.getItems().stream().map(i -> i.getMedicine().getId()).toList())
                .stream().collect(Collectors.toMap(MedicineStock::getMedicineId, MedicineStock::getQuantityOnHand));
        List<Line> lines = p.getItems().stream().map(i -> line(i, onHand)).toList();
        return new Found(p.getId(), p.getId().toString().substring(0, 8), p.getPatient().displayName(),
                p.getDoctor().getUser().getFullName(), p.getDiagnosis(), p.getIssuedAt(), dispensedAt,
                dispensedAt == null && !lines.isEmpty(), lines);
    }

    private static Line line(PrescriptionItem i, Map<UUID, Integer> onHand) {
        return new Line(i.getMedicine().getId(), i.getMedicine().getName(), i.getMedicine().getStrength(),
                i.getQuantity(), i.getDosage(), i.getFrequency(), i.getDurationDays(),
                onHand.getOrDefault(i.getMedicine().getId(), 0));
    }
}
