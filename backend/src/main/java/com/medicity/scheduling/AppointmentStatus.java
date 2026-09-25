package com.medicity.scheduling;

public enum AppointmentStatus {
    BOOKED,
    COMPLETED,
    CANCELLED,
    NO_SHOW;

    /** Statuses that hold the slot. Mirrors the partial unique index in V2. */
    public boolean holdsSlot() {
        return this != CANCELLED;
    }
}
