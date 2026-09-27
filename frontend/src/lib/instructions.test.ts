import { describe, expect, it } from "vitest";
import type { Prescription } from "../api/types";
import { FREQUENCY_SUGGESTIONS, instruction, parseFrequency, shareText } from "./instructions";

describe("parseFrequency", () => {
  it("reads how often, when, food and 'only when needed' from the doctor's words", () => {
    expect(parseFrequency("Once daily, before breakfast")).toMatchObject({
      timesPerDay: 1,
      food: "beforeBreakfast",
      understood: true,
    });
    expect(parseFrequency("Twice daily after food")).toMatchObject({ timesPerDay: 2, food: "after", understood: true });
    expect(parseFrequency("Three times daily")).toMatchObject({ timesPerDay: 3, understood: true });
    expect(parseFrequency("At night")).toMatchObject({ slots: ["night"], understood: true });
    expect(parseFrequency("As needed for fever, at most 3 a day")).toMatchObject({
      asNeeded: true,
      purpose: "fever",
      maxPerDay: 3,
      understood: true,
    });
    expect(parseFrequency("BD")).toMatchObject({ timesPerDay: 2, understood: true });
  });

  it("gives up on anything it does not fully recognise, rather than guess", () => {
    expect(parseFrequency("Twice daily, reduce to once after a week").understood).toBe(false);
    expect(parseFrequency("Apply on the rash").understood).toBe(false);
    // Recognising only filler words is not an instruction.
    expect(parseFrequency("take the tablet").understood).toBe(false);
  });

  it("recognises every phrase it suggests to doctors", () => {
    for (const phrase of FREQUENCY_SUGGESTIONS) {
      expect(parseFrequency(phrase).understood, phrase).toBe(true);
    }
  });
});

describe("instruction", () => {
  it("writes each part from the phrasebook, with the course length", () => {
    expect(instruction("Twice daily after food", 5, "hi").parts).toEqual(["दिन में दो बार", "खाने के बाद", "5 दिन तक"]);
    expect(instruction("Once daily, before breakfast", 14, "ta").parts).toEqual([
      "ஒரு நாளைக்கு ஒரு முறை",
      "காலை உணவுக்கு முன்",
      "14 நாட்களுக்கு",
    ]);
    expect(instruction("As needed for fever, at most 3 a day", 5, "en").parts).toEqual([
      "only when needed",
      "for fever",
      "not more than 3 a day",
      "for 5 days",
    ]);
  });

  it("asks the patient to check with the pharmacist when the words were not understood", () => {
    const unclear = instruction("Taper over two weeks", 14, "kn");
    expect(unclear.understood).toBe(false);
    expect(unclear.parts).toEqual(["ಈ ಸೂಚನೆಗಳನ್ನು ನಿಮ್ಮ ಔಷಧಿಕಾರರಿಂದ ಕೇಳಿ ತಿಳಿಯಿರಿ."]);
  });
});

describe("shareText", () => {
  const rx: Prescription = {
    id: "p1",
    appointmentId: "a1",
    issuedAt: "2030-01-01T05:00:00Z",
    doctorName: "Dr. Anjali Rao",
    specialization: "Cardiology",
    diagnosis: "Gastro-oesophageal reflux",
    notes: null,
    revised: false,
    dispensedAt: null,
    collectedAt: null,
    collectedFrom: null,
    hasPhoto: false,
    items: [
      {
        medicineId: "m1",
        medicine: "Omeprazole",
        strength: "20mg",
        form: "CAPSULE",
        dosage: "20mg",
        frequency: "Once daily, before breakfast",
        durationDays: 14,
        quantity: 14,
        genericName: "Omeprazole",
        substitutionAllowed: true,
      },
    ],
  };

  it("sends the medicines and how to take them, with the doctor's words, but never the diagnosis", () => {
    const text = shareText(rx, "hi");
    expect(text).toContain("Omeprazole 20mg 20mg");
    expect(text).toContain("दिन में एक बार · नाश्ते से पहले · 14 दिन तक");
    expect(text).toContain("(Once daily, before breakfast, 14 days)");
    expect(text).not.toContain("reflux");
  });
});
