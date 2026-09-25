package com.medicity.scheduling;

public enum SlotStatus {
    /** Accepts bookings. */
    OPEN,
    /** Held by the doctor or an admin (leave, surgery, conference) — never bookable. */
    BLOCKED
}
