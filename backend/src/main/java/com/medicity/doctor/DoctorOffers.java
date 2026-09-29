package com.medicity.doctor;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;

/**
 * What a page of the directory shows beside each doctor: accepted insurers
 * and the price list. One query each for the whole page, like the next free
 * times and the ratings, never one per card.
 */
@Component
@RequiredArgsConstructor
public class DoctorOffers {

    private final NamedParameterJdbcTemplate jdbc;

    public record Price(String procedure, int priceInr, boolean everyVisit) {}

    public record Insurer(String name, String kind) {}

    /** Every insurer the filter can offer, private first, then public and government schemes. */
    public List<Insurer> insurers() {
        return jdbc.query("""
                SELECT name, kind FROM insurers
                ORDER BY CASE kind WHEN 'PRIVATE' THEN 0 WHEN 'PUBLIC' THEN 1 ELSE 2 END, name
                """, (rs, n) -> new Insurer(rs.getString("name"), rs.getString("kind")));
    }

    public Map<UUID, List<String>> insurersOf(Collection<UUID> doctorIds) {
        Map<UUID, List<String>> out = new LinkedHashMap<>();
        if (doctorIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT doctor_id, insurer FROM doctor_insurance WHERE doctor_id IN (:ids) ORDER BY insurer",
                Map.of("ids", doctorIds), rs -> {
                    out.computeIfAbsent(rs.getObject("doctor_id", UUID.class), k -> new ArrayList<>())
                            .add(rs.getString("insurer"));
                });
        return out;
    }

    /** Every-visit charges first, then the rest cheapest first. */
    public Map<UUID, List<Price>> pricesOf(Collection<UUID> doctorIds) {
        Map<UUID, List<Price>> out = new LinkedHashMap<>();
        if (doctorIds.isEmpty()) {
            return out;
        }
        jdbc.query("""
                SELECT doctor_id, procedure, price_inr, every_visit FROM doctor_procedure_prices
                WHERE doctor_id IN (:ids) ORDER BY every_visit DESC, price_inr, procedure
                """, Map.of("ids", doctorIds), rs -> {
            out.computeIfAbsent(rs.getObject("doctor_id", UUID.class), k -> new ArrayList<>())
                    .add(new Price(rs.getString("procedure"), rs.getInt("price_inr"), rs.getBoolean("every_visit")));
        });
        return out;
    }
}
