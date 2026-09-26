import { describe, expect, it } from "vitest";
import { ageFrom, firstName, greeting, initials } from "./format";

describe("ageFrom", () => {
  const now = new Date(2026, 8, 27); // 27 September 2026, local time

  it("counts a birthday that has passed this year", () => {
    expect(ageFrom("1990-03-01", now)).toBe(36);
  });

  it("does not count one still to come", () => {
    expect(ageFrom("1990-12-01", now)).toBe(35);
  });

  it("counts the birthday on the day itself", () => {
    expect(ageFrom("1990-09-27", now)).toBe(36);
    expect(ageFrom("1990-09-28", now)).toBe(35);
  });
});

describe("names", () => {
  it("drops the Dr. title from initials", () => {
    expect(initials("Dr. Anjali Rao")).toBe("AR");
    expect(initials("Dr Suresh Iyer")).toBe("SI");
    expect(initials("Meera Nair")).toBe("MN");
  });

  it("takes the first name", () => {
    expect(firstName("Meera Nair")).toBe("Meera");
  });
});

describe("greeting", () => {
  it("follows the time of day", () => {
    expect(greeting(new Date(2026, 0, 1, 9))).toBe("Good morning");
    expect(greeting(new Date(2026, 0, 1, 14))).toBe("Good afternoon");
    expect(greeting(new Date(2026, 0, 1, 20))).toBe("Good evening");
  });
});
