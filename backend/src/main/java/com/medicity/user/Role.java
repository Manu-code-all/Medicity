package com.medicity.user;

/**
 * Authorisation roles. Deliberately coarse: fine-grained permissions are
 * expressed as method-level rules over these, not as a permission table,
 * because the domain has exactly this many distinct viewpoints.
 *
 * <p>{@code CHEMIST} runs a neighbourhood store outside the hospital. It sees
 * only what patients send to its store, and nothing until an administrator
 * has verified the store's drug licence.
 *
 * <p>{@code PHARMACIST} works the hospital pharmacy counter: dispenses and
 * restocks, but cannot read the audit log (that stays with {@code ADMIN}).
 *
 * <p>{@code CLINIC_ADMIN} and {@code RECEPTIONIST} belong to one clinic (the
 * {@code clinic_staff} row, V44) and see nothing outside it. The admin manages
 * the clinic's profile, doctors, receptionists and report; the receptionist
 * works the front desk.
 */
public enum Role {
    PATIENT,
    DOCTOR,
    ADMIN,
    CHEMIST,
    PHARMACIST,
    CLINIC_ADMIN,
    RECEPTIONIST;

    /** Spring Security expects the {@code ROLE_} prefix on authorities. */
    public String authority() {
        return "ROLE_" + name();
    }
}
