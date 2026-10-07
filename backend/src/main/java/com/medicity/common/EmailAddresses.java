package com.medicity.common;

/** Email addresses as people type them, and as they are shown back. */
public final class EmailAddresses {

    private EmailAddresses() {
    }

    /** Trimmed and lower case, the form {@code users.email} is stored in; {@code null} stays {@code null}. */
    public static String normalise(String typed) {
        return typed == null ? null : typed.trim().toLowerCase();
    }

    /**
     * "m•••@gmail.com": enough for the owner to recognise the address, not
     * enough to copy it. The domain is kept whole because it is what tells a
     * person which of their addresses this is.
     */
    public static String masked(String email) {
        int at = email.lastIndexOf('@');
        if (at < 1) {
            return "•••";
        }
        return email.charAt(0) + "•••" + email.substring(at);
    }
}
