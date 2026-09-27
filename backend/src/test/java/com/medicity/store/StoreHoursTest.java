package com.medicity.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Store opening hours")
class StoreHoursTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    @DisplayName("daytime hours: open from opening time, closed from closing time")
    void daytime() {
        LocalTime opens = LocalTime.of(9, 0);
        LocalTime closes = LocalTime.of(21, 0);
        assertThat(StoreHours.isOpen(false, opens, closes, at(8, 59))).isFalse();
        assertThat(StoreHours.isOpen(false, opens, closes, at(9, 0))).isTrue();
        assertThat(StoreHours.isOpen(false, opens, closes, at(20, 59))).isTrue();
        assertThat(StoreHours.isOpen(false, opens, closes, at(21, 0))).isFalse();
    }

    @Test
    @DisplayName("past midnight: 20:00 to 02:00 is open late at night and early in the morning")
    void pastMidnight() {
        LocalTime opens = LocalTime.of(20, 0);
        LocalTime closes = LocalTime.of(2, 0);
        assertThat(StoreHours.isOpen(false, opens, closes, at(23, 30))).isTrue();
        assertThat(StoreHours.isOpen(false, opens, closes, at(1, 59))).isTrue();
        assertThat(StoreHours.isOpen(false, opens, closes, at(2, 0))).isFalse();
        assertThat(StoreHours.isOpen(false, opens, closes, at(12, 0))).isFalse();
    }

    @Test
    @DisplayName("a 24-hour store is always open, whatever times are stored")
    void allDay() {
        assertThat(StoreHours.isOpen(true, LocalTime.of(9, 0), LocalTime.of(10, 0), at(3, 0))).isTrue();
    }

    @Test
    @DisplayName("hours are the store's local time, not the server's")
    void localTime() {
        Store store = Store.builder().opensAt(LocalTime.of(9, 0)).closesAt(LocalTime.of(21, 0)).build();
        // 04:00 UTC is 09:30 in India.
        assertThat(store.isOpenAt(ZonedDateTime.of(2026, 9, 27, 4, 0, 0, 0, ZoneId.of("UTC")).toInstant())).isTrue();
        // 16:00 UTC is 21:30 in India.
        assertThat(store.isOpenAt(ZonedDateTime.of(2026, 9, 27, 16, 0, 0, 0, ZoneId.of("UTC")).toInstant())).isFalse();
    }

    private static ZonedDateTime at(int hour, int minute) {
        return ZonedDateTime.of(2026, 9, 27, hour, minute, 0, 0, IST);
    }
}
