import { describe, expect, it } from "vitest";
import { SILHOUETTES } from "./silhouettes";
import { BODY_TAXONOMY, SPECIALIST, readDescription, recommend, regionsIn, specialistPhrase, wordMatches } from "./taxonomy";

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

  it("reads warning signs in the visitor's own words, erring towards emergency", () => {
    for (const text of [
      "I can't breathe properly",
      "my dad fainted after lunch",
      "her face is drooping on one side",
      "coughing up blood since morning",
      "pain spreading to my left arm",
      "worst headache of my life",
      "a seizure an hour ago",
    ]) {
      expect(readDescription(text).emergency, text).toBe(true);
    }
    expect(readDescription("I can\u2019t breathe").emergency).toBe(true);
    expect(readDescription("sharp pain when I climb stairs").emergency).toBe(false);
    expect(readDescription("I want to end my life")).toMatchObject({ emergency: true, crisis: true });
  });

  it("finds an everyday word in the description, as a whole word only", () => {
    expect(readDescription("my back tooth hurts when I chew")).toMatchObject({ word: "tooth", specialty: "Dentistry" });
    expect(readDescription("itchy rashes on both arms")).toMatchObject({ specialty: "Dermatology" });
    // "ear" is not in "heart" or "year"; "heart" is its own word.
    expect(readDescription("for a year my heart races")).toMatchObject({ word: "heart", specialty: "Cardiology" });
    expect(readDescription("sore since yesterday").specialty).toBeUndefined();
  });

  it("combines typed words with ticks: warning signs win, then ticks; words decide only when nothing is ticked", () => {
    expect(recommend("head_face", [], "my tooth aches")).toMatchObject({
      emergency: false,
      specialty: "Dentistry",
      rationale: "You mentioned \u201ctooth\u201d, which a dentist looks after.",
    });
    expect(recommend("abdomen_upper", ["burning_reflux"], "my skin itches too").specialty).toBe("Gastroenterology");
    expect(recommend("chest", ["palpitations"], "and I passed out").emergency).toBe(true);
    expect(recommend("chest", [], "a dull ache").specialty).toBe(BODY_TAXONOMY.chest!.routing.primarySpecialty);
  });
});

