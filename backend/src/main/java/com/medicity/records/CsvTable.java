package com.medicity.records;

import com.medicity.common.ValidationException;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A CSV file as the spreadsheet programs write it, read into rows of text.
 *
 * <p>What it copes with, because clinics export from Excel and not from code:
 * <ul>
 *   <li><b>Encoding.</b> UTF-8 with or without a byte-order mark; UTF-16 with a
 *       mark ("Unicode text" in Excel); and, when the bytes are not valid UTF-8,
 *       Windows-1252, which is what Excel's plain "CSV (Comma delimited)" writes
 *       on Windows.</li>
 *   <li><b>Delimiter.</b> Comma, semicolon (Excel in most of Europe and in some
 *       Indian locales) or tab, found from the first line; an Excel
 *       {@code sep=;} first line is honoured and dropped.</li>
 *   <li><b>Quoting.</b> Fields in double quotes, {@code ""} for a quote, and
 *       line breaks inside quotes.</li>
 *   <li><b>Empty rows.</b> A row of only empty cells (Excel's trailing
 *       {@code ,,,,}) is dropped.</li>
 * </ul>
 * It never logs, and its errors never repeat what was in the file.
 */
final class CsvTable {

    /** Cell text, one list per row, the header row first. Rows may be ragged. */
    private final List<List<String>> rows;
    /** The spreadsheet row number of each entry of {@link #rows}: empty rows are dropped but still counted. */
    private final List<Integer> numbers;
    private final char delimiter;

    private CsvTable(List<List<String>> rows, List<Integer> numbers, char delimiter) {
        this.rows = rows;
        this.numbers = numbers;
        this.delimiter = delimiter;
    }

    List<Integer> numbers() {
        return numbers;
    }

    List<List<String>> rows() {
        return rows;
    }

    char delimiter() {
        return delimiter;
    }

    /**
     * @param maxRows the most records (header included) to read; one more is a
     *                {@code TOO_MANY_ROWS} refusal, raised before the rest is parsed
     */
    static CsvTable parse(byte[] bytes, int maxRows) {
        String text = decode(bytes);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }

        char delimiter = ',';
        if (text.regionMatches(true, 0, "sep=", 0, 4) && text.length() > 4 && isDelimiter(text.charAt(4))) {
            delimiter = text.charAt(4);
            int eol = text.indexOf('\n');
            text = eol < 0 ? "" : text.substring(eol + 1);
        } else {
            delimiter = sniffDelimiter(text);
        }

        List<List<String>> rows = new ArrayList<>();
        List<Integer> numbers = new ArrayList<>();
        int[] seen = {0};
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;      // inside "..."
        boolean wasQuoted = false;   // this cell began with a quote (so an empty "" is still a cell)
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.length() == 0 && !wasQuoted) {
                quoted = true;
                wasQuoted = true;
            } else if (c == delimiter) {
                row.add(cell.toString());
                cell.setLength(0);
                wasQuoted = false;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                wasQuoted = false;
                addUnlessEmpty(rows, numbers, seen, row, maxRows);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (quoted) {
            throw new ValidationException("BAD_CSV",
                    "A quote in the file is never closed. Save the sheet as CSV again and retry.");
        }
        if (cell.length() > 0 || !row.isEmpty() || wasQuoted) {
            row.add(cell.toString());
            addUnlessEmpty(rows, numbers, seen, row, maxRows);
        }
        return new CsvTable(rows, numbers, delimiter);
    }

    private static void addUnlessEmpty(List<List<String>> rows, List<Integer> numbers, int[] seen,
                                       List<String> row, int maxRows) {
        seen[0]++;
        if (row.stream().allMatch(s -> s.isBlank())) {
            return;
        }
        if (rows.size() >= maxRows) {
            throw new ValidationException("TOO_MANY_ROWS",
                    "The file has more than " + (maxRows - 1) + " rows. Split it into smaller files.");
        }
        rows.add(row);
        numbers.add(seen[0]);
    }

    private static boolean isDelimiter(char c) {
        return c == ',' || c == ';' || c == '\t';
    }

    /** The most frequent of , ; and tab outside quotes on the first non-empty line; comma when none. */
    private static char sniffDelimiter(String text) {
        int comma = 0;
        int semicolon = 0;
        int tab = 0;
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted) {
                if (c == '\n' || c == '\r') {
                    if (any) {
                        break;
                    }
                } else {
                    any = true;
                    if (c == ',') comma++;
                    else if (c == ';') semicolon++;
                    else if (c == '\t') tab++;
                }
            }
        }
        if (semicolon > comma && semicolon >= tab) {
            return ';';
        }
        if (tab > comma && tab > semicolon) {
            return '\t';
        }
        return ',';
    }

    private static String decode(byte[] b) {
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        }
        for (byte x : b) {
            if (x == 0) {
                // An .xlsx is a zip file, an .xls a binary one; both are full of zero bytes.
                throw new ValidationException("NOT_A_CSV",
                        "This is not a CSV text file. In Excel choose Save As, then CSV (Comma delimited) or CSV UTF-8.");
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException notUtf8) {
            return new String(b, Charset.forName("windows-1252"));
        }
    }
}
