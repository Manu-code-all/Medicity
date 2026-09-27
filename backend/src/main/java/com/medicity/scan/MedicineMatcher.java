package com.medicity.scan;

import com.medicity.scan.PrescriptionReader.ReadLine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Finds the catalogue medicine a read line names, or admits it cannot.
 *
 * <p>Deliberately strict. A match needs the name (brand or generic) to be
 * exactly a catalogue entry's, ignoring case and spacing, and the strength to
 * agree when both are known. When more than one entry fits, nothing is
 * chosen: picking "the likely one" between two strengths is precisely the
 * mistake the doctor's review exists to catch, and an empty field is harder
 * to overlook than a wrong one.
 */
@Component
public class MedicineMatcher {

    private final JdbcTemplate jdbc;

    public MedicineMatcher(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Match match(ReadLine line) {
        String name = normalise(line.medicine());
        if (name.isEmpty()) {
            return null;
        }
        String strength = normalise(line.strength());
        List<Match> candidates = jdbc.query("""
                SELECT id, name, strength FROM medicines
                WHERE active
                  AND (regexp_replace(lower(name), '\\s+', '', 'g') = ?
                       OR regexp_replace(lower(generic_name), '\\s+', '', 'g') = ?)
                  AND (? = '' OR regexp_replace(lower(coalesce(strength, '')), '\\s+', '', 'g') = ?)
                ORDER BY name
                LIMIT 3
                """, (rs, i) -> new Match(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("strength")),
                name, name, strength, strength);
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private static String normalise(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    public record Match(UUID medicineId, String name, String strength) {}
}
