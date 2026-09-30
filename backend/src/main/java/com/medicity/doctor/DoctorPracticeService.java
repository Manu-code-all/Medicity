package com.medicity.doctor;

import com.medicity.audit.AuditLog;
import com.medicity.common.NotFoundException;
import com.medicity.common.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What a doctor tells patients about their practice after signing up: the
 * fee, the bio, years in practice, the insurers accepted and the price list.
 * These were fixed at sign-up (fee, bio) or seeded (insurers, prices); now the
 * doctor keeps them current.
 *
 * <p>Saving replaces the whole practice at once, like the weekly hours and a
 * store's stock list: the form shows everything, so what comes back is
 * everything, and a price missing from it is a price withdrawn.
 */
@Service
@RequiredArgsConstructor
public class DoctorPracticeService {

    static final int MAX_PRICES = 20;

    private final NamedParameterJdbcTemplate jdbc;
    private final DoctorRepository doctors;
    private final DoctorOffers offers;
    private final AuditLog auditLog;

    public record Clinic(String name, String address, Double latitude, Double longitude) {}

    public record Practice(BigDecimal consultationFee, String bio, int yearsExperience, List<String> insurers,
                           List<DoctorOffers.Price> prices, List<DoctorOffers.Insurer> availableInsurers,
                           Clinic clinic) {}

    @Transactional(readOnly = true)
    public Practice practice(UUID doctorUserId) {
        Doctor d = requireDoctor(doctorUserId);
        return new Practice(d.getConsultationFee(), d.getBio(), d.getYearsExperience(),
                offers.insurersOf(List.of(d.getId())).getOrDefault(d.getId(), List.of()),
                offers.pricesOf(List.of(d.getId())).getOrDefault(d.getId(), List.of()),
                offers.insurers(),
                new Clinic(d.getClinicName(), d.getClinicAddress(), d.getClinicLatitude(), d.getClinicLongitude()));
    }

    @Transactional
    public Practice update(UUID doctorUserId, BigDecimal fee, String bio, int yearsExperience,
                           List<String> insurers, List<DoctorOffers.Price> prices, Clinic clinic) {
        Doctor d = requireDoctor(doctorUserId);
        if (clinic != null && (clinic.latitude() == null) != (clinic.longitude() == null)) {
            throw new ValidationException("LOCATION_INCOMPLETE", "Send both latitude and longitude, or neither");
        }

        Set<String> known = offers.insurers().stream().map(DoctorOffers.Insurer::name).collect(Collectors.toSet());
        Set<String> chosen = new HashSet<>(insurers);
        chosen.removeAll(known);
        if (!chosen.isEmpty()) {
            throw new ValidationException("UNKNOWN_INSURER", "Not an insurer Medicity lists: " + String.join(", ", chosen));
        }
        if (prices.size() > MAX_PRICES) {
            throw new ValidationException("TOO_MANY_PRICES", "List at most %d prices".formatted(MAX_PRICES));
        }
        // Procedures are compared ignoring case and spacing, so "ECG" and "ecg " cannot both be listed.
        Map<String, DoctorOffers.Price> byName = new LinkedHashMap<>();
        for (DoctorOffers.Price p : prices) {
            String name = p.procedure().trim().replaceAll("\\s+", " ");
            if (byName.putIfAbsent(name.toLowerCase(Locale.ROOT), new DoctorOffers.Price(name, p.priceInr(), p.everyVisit())) != null) {
                throw new ValidationException("DUPLICATE_PROCEDURE", "\"%s\" is listed twice".formatted(name));
            }
        }

        BigDecimal oldFee = d.getConsultationFee();
        d.setConsultationFee(fee);
        d.setBio(bio == null || bio.isBlank() ? null : bio.trim());
        d.setYearsExperience(yearsExperience);
        d.setClinicName(clinic == null ? null : blankToNull(clinic.name()));
        d.setClinicAddress(clinic == null ? null : blankToNull(clinic.address()));
        d.setClinicLatitude(clinic == null ? null : clinic.latitude());
        d.setClinicLongitude(clinic == null ? null : clinic.longitude());
        doctors.save(d);

        var id = new MapSqlParameterSource("doctor", d.getId());
        jdbc.update("DELETE FROM doctor_insurance WHERE doctor_id = :doctor", id);
        for (String insurer : new HashSet<>(insurers)) {
            jdbc.update("INSERT INTO doctor_insurance (doctor_id, insurer) VALUES (:doctor, :insurer)",
                    new MapSqlParameterSource("doctor", d.getId()).addValue("insurer", insurer));
        }
        jdbc.update("DELETE FROM doctor_procedure_prices WHERE doctor_id = :doctor", id);
        for (DoctorOffers.Price p : byName.values()) {
            jdbc.update("""
                    INSERT INTO doctor_procedure_prices (doctor_id, procedure, price_inr, every_visit)
                    VALUES (:doctor, :procedure, :price, :everyVisit)
                    """, new MapSqlParameterSource("doctor", d.getId()).addValue("procedure", p.procedure())
                    .addValue("price", p.priceInr()).addValue("everyVisit", p.everyVisit()));
        }

        auditLog.recordChange("DOCTOR_PRACTICE_UPDATED", "DOCTOR", d.getId(), Map.of(
                "feeFrom", oldFee, "feeTo", fee, "insurers", new HashSet<>(insurers).size(), "prices", byName.size()));
        return practice(doctorUserId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Doctor requireDoctor(UUID doctorUserId) {
        return doctors.findByUserId(doctorUserId)
                .orElseThrow(() -> new NotFoundException("Doctor for user", doctorUserId));
    }
}
