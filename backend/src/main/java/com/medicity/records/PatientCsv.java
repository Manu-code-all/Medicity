package com.medicity.records;

import com.medicity.common.EmailAddresses;
import com.medicity.common.ValidationException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a clinic's patient list: finds the columns by their headings, checks
 * every row, and keeps the rows that are good apart from the ones that are not.
 *
 * <p>Nothing here writes, logs or repeats cell text in a message: an issue
 * names the row, the column and what is wrong, never the value. (The error
 * report the doctor downloads is built from the file they already hold.)
 *
 * <p>Row numbers are the spreadsheet's: the heading is row 1, the first
 * patient row 2.
 */
final class PatientCsv {

    static final int MAX_ROWS = 5_000;
    static final int MAX_BYTES = 2 * 1024 * 1024;

    static final int NAME_MAX = 120;
    static final int ALLERGIES_MAX = 1_000;
    static final int NOTES_MAX = 4_000;

    /** The columns of the template, in order. */
    static final List<String> TEMPLATE_HEADINGS = List.of(
            "Full name", "Phone", "Email", "Date of birth", "Gender", "Blood group", "Allergies", "Notes");

    enum Field {
        NAME("Full name"), PHONE("Phone"), EMAIL("Email"), DOB("Date of birth"), GENDER("Gender"),
        BLOOD("Blood group"), ALLERGIES("Allergies"), NOTES("Notes");

        final String label;

        Field(String label) {
            this.label = label;
        }
    }

    /** Headings people really use, compared with everything but letters and digits removed. */
    private static final Map<String, Field> ALIASES = new HashMap<>();

    static {
        alias(Field.NAME, "fullname", "name", "patientname", "patient", "nameofpatient");
        alias(Field.PHONE, "phone", "mobile", "phonenumber", "mobilenumber", "mobileno", "phoneno", "contact",
                "contactnumber", "contactno", "telephone", "tel", "cell");
        alias(Field.EMAIL, "email", "emailaddress", "emailid", "mail");
        alias(Field.DOB, "dateofbirth", "dob", "birthdate", "birthday");
        alias(Field.GENDER, "gender", "sex");
        alias(Field.BLOOD, "bloodgroup", "bloodtype", "blood");
        alias(Field.ALLERGIES, "allergies", "allergy", "knownallergies");
        alias(Field.NOTES, "notes", "note", "remarks", "comments", "history", "clinicalnotes");
    }

    private static void alias(Field field, String... names) {
        for (String n : names) {
            ALIASES.put(n, field);
        }
    }

    /** One row that passed every check, in the form it is stored. */
    record Row(int row, String fullName, String phone, String email, LocalDate dateOfBirth, String gender,
               String bloodGroup, String allergies, String notes) {}

    /** One thing wrong with one row. {@code field} is a column label, or null when it is the row as a whole. */
    record Issue(int row, String field, String code, String message) {}

    /**
     * @param totalRows rows with something in them, not counting the heading
     * @param ignoredColumns headings that were not recognised and were left out
     * @param cells the original text of every row, by spreadsheet row number (for the error report)
     */
    record Parsed(int totalRows, List<Row> valid, List<Issue> issues, List<String> ignoredColumns,
                  Map<Integer, Map<Field, String>> cells) {
        int rejectedRows() {
            return (int) issues.stream().mapToInt(Issue::row).distinct().count();
        }
    }

    private static final Pattern EMAIL = Pattern.compile("[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+");
    private static final Pattern ISO = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[ T]00:00(?::00)?)?");
    private static final Pattern DAY_FIRST = Pattern.compile("(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})");
    private static final Pattern BLOOD = Pattern.compile("(AB|A|B|O)(\\+|-|POS|POSITIVE|NEG|NEGATIVE|\\+VE|-VE|POSITIVE|NEGATIVE)");

    private final Clock clock;

    PatientCsv(Clock clock) {
        this.clock = clock;
    }

    Parsed parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ValidationException("EMPTY_FILE", "The file is empty.");
        }
        if (bytes.length > MAX_BYTES) {
            throw new ValidationException("FILE_TOO_LARGE", "The file is larger than 2 MB. Split it into smaller files.");
        }
        CsvTable table = CsvTable.parse(bytes, MAX_ROWS + 1);
        if (table.rows().isEmpty()) {
            throw new ValidationException("EMPTY_FILE", "The file has no rows.");
        }

        List<String> header = table.rows().get(0);
        Map<Field, Integer> at = new LinkedHashMap<>();
        List<String> ignored = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            String heading = header.get(i).replace("﻿", "").trim();
            if (heading.isEmpty()) {
                continue;
            }
            Field f = ALIASES.get(heading.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", ""));
            if (f == null) {
                ignored.add(heading.length() > 40 ? heading.substring(0, 40) : heading);
            } else if (at.putIfAbsent(f, i) != null) {
                throw new ValidationException("DUPLICATE_COLUMN",
                        "The column \"" + f.label + "\" appears twice. Keep one of them.");
            }
        }
        if (!at.containsKey(Field.NAME)) {
            throw new ValidationException("MISSING_COLUMN",
                    "The first row must have a \"Full name\" heading. Download the template to see the columns.");
        }
        if (!at.containsKey(Field.PHONE) && !at.containsKey(Field.EMAIL)) {
            throw new ValidationException("MISSING_COLUMN",
                    "The file needs a \"Phone\" or an \"Email\" column: they are how a patient is recognised if you import the list again.");
        }

        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        List<Row> valid = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();
        Map<Integer, Map<Field, String>> cells = new LinkedHashMap<>();
        Map<String, Integer> emailRows = new HashMap<>();
        Map<String, Integer> phoneRows = new HashMap<>();

        for (int r = 1; r < table.rows().size(); r++) {
            int rowNo = table.numbers().get(r);
            List<String> raw = table.rows().get(r);
            Map<Field, String> cell = new LinkedHashMap<>();
            for (Map.Entry<Field, Integer> e : at.entrySet()) {
                cell.put(e.getKey(), e.getValue() < raw.size() ? clean(raw.get(e.getValue()), e.getKey()) : "");
            }
            cells.put(rowNo, cell);

            List<Issue> mine = new ArrayList<>();
            String name = cell.getOrDefault(Field.NAME, "");
            if (name.isEmpty()) {
                mine.add(new Issue(rowNo, Field.NAME.label, "NAME_MISSING", "The name is empty."));
            } else if (name.length() > NAME_MAX) {
                mine.add(new Issue(rowNo, Field.NAME.label, "NAME_TOO_LONG", "The name is longer than " + NAME_MAX + " characters."));
            }

            String phone = null;
            String phoneText = cell.getOrDefault(Field.PHONE, "");
            if (!phoneText.isEmpty()) {
                phone = phone(phoneText);
                if (phone == null) {
                    mine.add(new Issue(rowNo, Field.PHONE.label, "PHONE_INVALID", phoneProblem(phoneText)));
                }
            }

            String email = null;
            String emailText = cell.getOrDefault(Field.EMAIL, "");
            if (!emailText.isEmpty()) {
                email = EmailAddresses.normalise(emailText);
                if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
                    mine.add(new Issue(rowNo, Field.EMAIL.label, "EMAIL_INVALID", "This is not an email address."));
                    email = null;
                }
            }
            if (phoneText.isEmpty() && emailText.isEmpty()) {
                mine.add(new Issue(rowNo, null, "NO_CONTACT",
                        "The row has neither a phone nor an email, so it could not be recognised on a later import."));
            }

            LocalDate dob = null;
            String dobText = cell.getOrDefault(Field.DOB, "");
            if (!dobText.isEmpty()) {
                dob = date(dobText, today);
                if (dob == null) {
                    mine.add(new Issue(rowNo, Field.DOB.label, "DOB_INVALID",
                            "Use DD/MM/YYYY or YYYY-MM-DD, a real date in the past (not before 1900)."));
                }
            }

            String gender = null;
            String genderText = cell.getOrDefault(Field.GENDER, "");
            if (!genderText.isEmpty()) {
                gender = gender(genderText);
                if (gender == null) {
                    mine.add(new Issue(rowNo, Field.GENDER.label, "GENDER_INVALID",
                            "Use Male, Female, Other or leave it empty."));
                }
            }

            String blood = null;
            String bloodText = cell.getOrDefault(Field.BLOOD, "");
            if (!bloodText.isEmpty()) {
                blood = blood(bloodText);
                if (blood == null) {
                    mine.add(new Issue(rowNo, Field.BLOOD.label, "BLOOD_GROUP_INVALID",
                            "Use A+, A-, B+, B-, AB+, AB-, O+ or O-."));
                }
            }

            String allergies = emptyToNull(cell.getOrDefault(Field.ALLERGIES, ""));
            if (allergies != null && allergies.length() > ALLERGIES_MAX) {
                mine.add(new Issue(rowNo, Field.ALLERGIES.label, "ALLERGIES_TOO_LONG",
                        "Longer than " + ALLERGIES_MAX + " characters."));
            }
            String notes = emptyToNull(cell.getOrDefault(Field.NOTES, ""));
            if (notes != null && notes.length() > NOTES_MAX) {
                mine.add(new Issue(rowNo, Field.NOTES.label, "NOTES_TOO_LONG", "Longer than " + NOTES_MAX + " characters."));
            }

            // The same person twice in one file: the first row wins, later ones are reported.
            if (mine.isEmpty()) {
                Integer sameEmail = email == null ? null : emailRows.get(email);
                Integer samePhone = phone == null ? null : phoneRows.get(phone);
                if (sameEmail != null) {
                    mine.add(new Issue(rowNo, Field.EMAIL.label, "DUPLICATE_IN_FILE",
                            "Same email as row " + sameEmail + "."));
                }
                if (samePhone != null) {
                    mine.add(new Issue(rowNo, Field.PHONE.label, "DUPLICATE_IN_FILE",
                            "Same phone as row " + samePhone + "."));
                }
            }

            if (mine.isEmpty()) {
                if (email != null) emailRows.put(email, rowNo);
                if (phone != null) phoneRows.put(phone, rowNo);
                valid.add(new Row(rowNo, name, phone, email, dob, gender, blood, allergies, notes));
            } else {
                issues.addAll(mine);
            }
        }
        return new Parsed(table.rows().size() - 1, valid, issues, ignored, cells);
    }

    // --- field rules ----------------------------------------------------------

    /** Trimmed, control characters removed (a line break stays in notes). */
    private static String clean(String s, Field field) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '﻿' || c == ' ') {
                sb.append(c == ' ' ? ' ' : "");
            } else if (c == '\n' || c == '\r') {
                sb.append(field == Field.NOTES || field == Field.ALLERGIES ? '\n' : ' ');
            } else if (!Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private static String emptyToNull(String s) {
        return s.isEmpty() ? null : s;
    }

    /**
     * Digits only. A leading + or 00 and the Indian prefix (91, or a leading 0) are dropped so the
     * same number written three ways is one number; other countries keep their prefix.
     * Null when it is not 10 to 15 digits.
     */
    static String phone(String typed) {
        String s = typed.trim();
        if (s.matches(".*\\d[eE][+-]?\\d.*") || s.matches(".*\\d\\.\\d.*")) {
            return null;   // 9.87654E+11: a spreadsheet turned the number into a float
        }
        boolean plus = s.startsWith("+");
        String digits = s.replaceAll("[\\s().\\-]", "");
        if (plus) {
            digits = digits.substring(1);
        } else if (digits.startsWith("00")) {
            digits = digits.substring(2);
            plus = true;
        }
        if (!digits.matches("\\d+")) {
            return null;
        }
        if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        } else if (digits.length() == 11 && digits.startsWith("0") && !plus) {
            digits = digits.substring(1);
        }
        return digits.length() >= 10 && digits.length() <= 15 ? digits : null;
    }

    private static String phoneProblem(String typed) {
        if (typed.matches(".*\\d[eE][+-]?\\d.*") || typed.matches(".*\\d\\.\\d.*")) {
            return "Excel has turned this number into a decimal or E+ form. Format the column as Text and type the number again.";
        }
        return "A phone number has 10 to 15 digits (spaces, dashes and a leading + are fine).";
    }

    /** DD/MM/YYYY, DD-MM-YYYY, DD.MM.YYYY (day first, as is usual in India) or YYYY-MM-DD; null if not a real past date. */
    static LocalDate date(String typed, LocalDate latest) {
        String s = typed.trim();
        int y;
        int m;
        int d;
        Matcher iso = ISO.matcher(s);
        Matcher dmy = DAY_FIRST.matcher(s);
        if (iso.matches()) {
            y = Integer.parseInt(iso.group(1));
            m = Integer.parseInt(iso.group(2));
            d = Integer.parseInt(iso.group(3));
        } else if (dmy.matches()) {
            d = Integer.parseInt(dmy.group(1));
            m = Integer.parseInt(dmy.group(2));
            y = Integer.parseInt(dmy.group(3));
        } else {
            return null;
        }
        try {
            LocalDate date = LocalDate.of(y, m, d);
            return y < 1900 || date.isAfter(latest) ? null : date;
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    static String gender(String typed) {
        return switch (typed.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z/]", "")) {
            case "m", "male", "man", "boy" -> "MALE";
            case "f", "female", "woman", "girl" -> "FEMALE";
            case "o", "other", "nonbinary", "thirdgender", "transgender" -> "OTHER";
            case "undisclosed", "unknown", "na", "n/a", "preferrnottosay", "prefernottosay" -> "UNDISCLOSED";
            default -> null;
        };
    }

    static String blood(String typed) {
        String s = typed.trim().toUpperCase(Locale.ROOT).replace('−', '-').replaceAll("[\\s_]", "");
        Matcher m = BLOOD.matcher(s);
        if (!m.matches()) {
            return null;
        }
        String sign = m.group(2);
        return m.group(1) + (sign.startsWith("+") || sign.startsWith("POS") ? "+" : "-");
    }
}
