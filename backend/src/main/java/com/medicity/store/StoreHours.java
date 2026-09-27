package com.medicity.store;

import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * Whether a store is open, from its local opening hours.
 *
 * <p>Hours are wall-clock times in the store's own zone, not instants: a store
 * that opens at 09:00 opens at 09:00 whatever the server's zone, and whatever
 * the viewer's. A closing time earlier than the opening time means the store
 * stays open past midnight (20:00 to 02:00).
 */
public final class StoreHours {

    private StoreHours() {
    }

    public static boolean isOpen(boolean open24h, LocalTime opensAt, LocalTime closesAt, ZonedDateTime localNow) {
        if (open24h) {
            return true;
        }
        LocalTime now = localNow.toLocalTime();
        if (opensAt.isBefore(closesAt)) {
            return !now.isBefore(opensAt) && now.isBefore(closesAt);
        }
        // Past midnight: open from opensAt to the end of the day, and from the
        // start of the day until closesAt.
        return !now.isBefore(opensAt) || now.isBefore(closesAt);
    }
}
