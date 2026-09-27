package com.medicity.common;

/**
 * Indian mobile numbers in one form, +91 followed by ten digits starting 6 to
 * 9, whatever way they were typed: "98765 00101", "+91-98765-00101",
 * "919876500101". The same rule as the V20 migration, so a number typed at
 * sign-in finds the account it was registered on.
 */
public final class PhoneNumbers {

    private PhoneNumbers() {
    }

    /** The normalised number, or {@code null} if it is not an Indian mobile number. */
    public static String normalise(String typed) {
        if (typed == null) {
            return null;
        }
        String digits = typed.replaceAll("[^0-9]", "");
        if (digits.matches("[6-9][0-9]{9}")) {
            return "+91" + digits;
        }
        if (digits.matches("91[6-9][0-9]{9}")) {
            return "+" + digits;
        }
        return null;
    }

    /** "+91 98765 •••01": enough for the owner to recognise, not enough to copy. */
    public static String masked(String normalised) {
        return "+91 " + normalised.substring(3, 8) + " •••" + normalised.substring(11);
    }
}
