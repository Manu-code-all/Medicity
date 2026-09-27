package com.medicity.user;

/**
 * Authorisation roles. Deliberately coarse: fine-grained permissions are
 * expressed as method-level rules over these, not as a permission table,
 * because the domain has exactly this many distinct viewpoints.
 *
 * <p>{@code CHEMIST} runs a neighbourhood store outside the hospital. It sees
 * only what patients send to its store, and nothing until an administrator
 * has verified the store's drug licence.
 */
public enum Role {
    PATIENT,
    DOCTOR,
    ADMIN,
    CHEMIST;

    /** Spring Security expects the {@code ROLE_} prefix on authorities. */
    public String authority() {
        return "ROLE_" + name();
    }
}
