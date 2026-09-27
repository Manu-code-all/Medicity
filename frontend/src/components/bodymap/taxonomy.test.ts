import { describe, expect, it } from "vitest";
import { SILHOUETTES } from "./silhouettes";
import { BODY_TAXONOMY, SPECIALIST, recommend, regionsIn, specialistPhrase, wordMatches } from "./taxonomy";

/** The specialities the demo seeds a doctor for; every recommendation must be bookable. */
const SEEDED = [
  "Cardiology", "Neurology", "Paediatrics", "General Medicine", "Gastroenterology", "Orthopaedics", "ENT",
  "Dermatology", "Pulmonology", "Urology", "Gynaecology", "Nephrology", "General Surgery", "Dentistry",
];

describe("body guide taxonomy", () => {
  it("routes every area and symptom to a speciality someone practises", () => {
    for (const region of Object.values(BODY_TAXONOMY)) {
      expect(SEEDED).toContain(region.routing.primarySpecialty);
      expect(SEEDED).toContain(region.routing.secondarySpecialty);
      for (const s of region.subSymptoms) if (s.specialty) expect(SEEDED).toContain(s.specialty);
      expect(region.subSymptoms.some((s) => s.isRedFlag)).toBe(true);
    }
    for (const name of SEEDED) expect(SPECIALIST[name]).toBeDefined();
  });

  it("draws exactly the areas each view lists, and nothing it does not know", () => {
    for (const view of ["front", "back"] as const) {
      expect(SILHOUETTES[view].map((s) => s.id).sort()).toEqual(regionsIn(view).map((r) => r.id).sort());
    }
  });

  it("any warning sign means emergency care, whatever else is ticked", () => {
    expect(recommend("chest", ["palpitations", "crushing_pain"]).emergency).toBe(true);
    expect(recommend("chest", ["palpitations"]).emergency).toBe(false);
  });

  it("a symptom that names a speciality wins over the area's default, and the default becomes the alternative", () => {
    expect(recommend("head_face", ["tooth"])).toMatchObject({ specialty: "Dentistry", alternative: "Neurology" });
    expect(recommend("abdomen_upper", ["burning_reflux"])).toMatchObject({
      specialty: "Gastroenterology",
      alternative: "General Medicine",
    });
  });

  it("names the doctor with the right article", () => {
    expect(specialistPhrase("Orthopaedics")).toBe("an orthopaedic doctor");
    expect(specialistPhrase("ENT")).toBe("an ENT specialist");
    expect(specialistPhrase("Gastroenterology")).toBe("a gastroenterologist");
  });

  it("turns everyday words into specialities, never into an emergency symptom", () => {
    expect(wordMatches("knee")[0]).toMatchObject({ specialty: "Orthopaedics", zone: "joints_lower" });
    expect(wordMatches("skin")[0]).toMatchObject({ specialty: "Dermatology" });
    expect(wordMatches("tooth").map((m) => m.specialty)).toContain("Dentistry");
    expect(wordMatches("crushing")).toEqual([]);
    expect(wordMatches("ab")).toEqual([]);
    for (const m of wordMatches("pain", 10)) expect(SPECIALIST[m.specialty]).toBeDefined();
  });
});

