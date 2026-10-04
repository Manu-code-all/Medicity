package com.medicity.chemist;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The open-now rule, with no database: it is pure arithmetic on a time of day,
 * and the boundaries (the exact opening and closing minute, midnight) are where
 * it would go wrong.
 */
@DisplayName("Pharmacy opening hours")
class PharmacyHoursTest {

    private static final String IST = "Asia/Kolkata";   // UTC+05:30

    /** 12:00 IST is 06:30 UTC. */
    private static Instant ist(String time) {
        LocalTime t = LocalTime.parse(time);
        return Instant.parse("2026-03-10T00:00:00Z").plusSeconds(t.toSecondOfDay()).minusSeconds(5 * 3600 + 1800);
    }

    private static boolean open(String opens, String closes, String at) {
        return Pharmacy.isOpen(LocalTime.parse(opens), LocalTime.parse(closes), IST, ist(at));
    }

    @Test
    @DisplayName("open inside the window, closed outside it")
    void insideAndOutside() {
        assertThat(open("09:00", "21:00", "12:00")).isTrue();
        assertThat(open("09:00", "21:00", "08:59")).isFalse();
        assertThat(open("09:00", "21:00", "23:00")).isFalse();
    }

    @Test
    @DisplayName("opens inclusive, closes exclusive")
    void boundaries() {
        assertThat(open("09:00", "21:00", "09:00")).isTrue();
        assertThat(open("09:00", "21:00", "20:59")).isTrue();
        assertThat(open("09:00", "21:00", "21:00")).isFalse();
    }

    @Test
    @DisplayName("a shop closing after midnight stays open across it")
    void pastMidnight() {
        assertThat(open("22:00", "02:00", "23:30")).isTrue();
        assertThat(open("22:00", "02:00", "00:30")).isTrue();
        assertThat(open("22:00", "02:00", "01:59")).isTrue();
        assertThat(open("22:00", "02:00", "02:00")).isFalse();
        assertThat(open("22:00", "02:00", "12:00")).isFalse();
        assertThat(open("22:00", "02:00", "21:59")).isFalse();
    }

    @Test
    @DisplayName("hours are read on the shop's own clock, not UTC")
    void usesShopTimezone() {
        // 04:00 UTC is 09:30 in Kolkata (open) but 20:00 the evening before in New York.
        Instant instant = Instant.parse("2026-03-10T04:00:00Z");
        LocalTime opens = LocalTime.of(9, 0);
        LocalTime closes = LocalTime.of(21, 0);
        assertThat(Pharmacy.isOpen(opens, closes, "Asia/Kolkata", instant)).isTrue();
        assertThat(Pharmacy.isOpen(opens, closes, "America/New_York", instant)).isFalse();
    }
}
