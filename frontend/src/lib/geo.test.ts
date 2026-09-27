import { describe, expect, it } from "vitest";
import { formatDistance, formatStoreHours } from "./geo";

describe("formatDistance", () => {
  it("shows metres to the nearest 10 below a kilometre, never zero", () => {
    expect(formatDistance(347)).toBe("350 m");
    expect(formatDistance(2)).toBe("10 m");
  });

  it("shows kilometres with one decimal from a kilometre up", () => {
    expect(formatDistance(1000)).toBe("1.0 km");
    expect(formatDistance(2468)).toBe("2.5 km");
  });
});

describe("formatStoreHours", () => {
  it("shows local opening hours without seconds", () => {
    expect(formatStoreHours({ opensAt: "08:00:00", closesAt: "22:00:00", open24h: false })).toBe("08:00–22:00");
  });

  it("says a 24-hour store is open 24 hours, whatever times are stored", () => {
    expect(formatStoreHours({ opensAt: "00:00:00", closesAt: "23:59:00", open24h: true })).toBe("Open 24 hours");
  });
});
