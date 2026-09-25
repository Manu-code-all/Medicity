package com.medicity.user;

/**
 * Authorisation roles. Deliberately coarse: fine-grained permissions are
 * expressed as method-level rules over these three, not as a permission table,
 * because the domain has exactly three distinct viewpoints.
 */
public enum Role {
    PATIENT,
    DOCTOR,
    ADMIN;

    /** Spring Security expects the {@code ROLE_} prefix on authorities. */
    public String authority() {
        return "ROLE_" + name();
    }
}
