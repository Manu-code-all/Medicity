package com.medicity.clinical;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The ICD-10 codes a prescription can carry (V23). A reference table read
 * with plain SQL: it has no behaviour, never changes at runtime, and is
 * small enough (about 130 rows) that a scan per keystroke is cheaper than
 * maintaining a text index.
 */
@Component
public class DiagnosisCodes {

    private static final int LIMIT = 8;

    private final JdbcTemplate jdbc;

    public DiagnosisCodes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Code(String code, String title) {}

    /**
     * Codes matching what the doctor typed. Every word must appear in the
     * code, the title or the everyday keywords ("gerd", "bp", "sugar").
     * A code typed as a code ranks first, then a whole-word match ("bp"
     * is a keyword of hypertension, and only a fragment of "bppv"), then
     * titles that start with the text, then the rest alphabetically by code.
     */
    public List<Code> search(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.length() < 2) {
            return List.of();
        }
        List<String> words = Arrays.stream(q.split("\\s+")).map(DiagnosisCodes::like).toList();
        StringBuilder where = new StringBuilder("TRUE");
        for (int i = 0; i < words.size(); i++) {
            where.append(" AND (lower(code) LIKE ? OR lower(title) LIKE ? OR keywords LIKE ?)");
        }
        Object[] args = new Object[words.size() * 3 + 4];
        int i = 0;
        for (String w : words) {
            args[i++] = w;
            args[i++] = w;
            args[i++] = w;
        }
        args[i++] = likePrefix(q);
        args[i++] = "% " + escape(q) + " %";
        args[i++] = likePrefix(q);
        args[i] = LIMIT;
        return jdbc.query("""
                SELECT code, title FROM icd10_codes
                WHERE %s
                ORDER BY (lower(code) LIKE ?) DESC,
                         (' ' || regexp_replace(lower(title), '[^a-z0-9]+', ' ', 'g') || ' ' || keywords || ' ' LIKE ?) DESC,
                         (lower(title) LIKE ?) DESC,
                         code
                LIMIT ?
                """.formatted(where), (rs, n) -> new Code(rs.getString("code"), rs.getString("title")), args);
    }

    public boolean exists(String code) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM icd10_codes WHERE code = ?", Integer.class, code);
        return n != null && n > 0;
    }

    /** A LIKE pattern for a word anywhere, with the user's % and _ taken literally. */
    private static String like(String word) {
        return "%" + escape(word) + "%";
    }

    private static String likePrefix(String text) {
        return escape(text) + "%";
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
