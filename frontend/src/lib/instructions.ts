/**
 * "How to take it", in the patient's own language.
 *
 * Doctors write the frequency as short free text ("Twice daily after food").
 * Machine-translating free text into medical instructions is how a patient
 * ends up taking a tablet three times instead of once, so this does not
 * translate text. It recognises a fixed set of phrases, turns them into
 * structured parts (how often, when, food, only when needed, a daily maximum,
 * what for), and writes each part from a phrasebook. Anything it does not
 * recognise makes the whole instruction "not understood": the patient then sees
 * the doctor's English words, and "ask your pharmacist to explain", never a
 * partial guess.
 */

import type { Prescription } from "../api/types";
import { formatDate } from "./format";

export type Language = "en" | "hi" | "ta" | "kn" | "te" | "bn";

export const LANGUAGES: { code: Language; name: string }[] = [
  { code: "en", name: "English" },
  { code: "hi", name: "हिन्दी" },
  { code: "ta", name: "தமிழ்" },
  { code: "kn", name: "ಕನ್ನಡ" },
  { code: "te", name: "తెలుగు" },
  { code: "bn", name: "বাংলা" },
];

type Slot = "morning" | "afternoon" | "night" | "bedtime";
type Food = "before" | "after" | "with" | "empty" | "beforeBreakfast";
type Purpose = "fever" | "pain";

export interface Parsed {
  timesPerDay: 1 | 2 | 3 | 4 | null;
  slots: Slot[];
  food: Food | null;
  asNeeded: boolean;
  maxPerDay: number | null;
  purpose: Purpose | null;
  /** False when any word was not recognised: show the doctor's own words instead. */
  understood: boolean;
}

/** Longest phrases first, so "before breakfast" wins over "before". */
const PHRASES: [RegExp, (p: Parsed, m: RegExpMatchArray) => void][] = [
  [/\b(?:at most|not more than|max(?:imum)?|up to) (\d) (?:a|per) day\b/, (p, m) => (p.maxPerDay = Number(m[1]))],
  [/\b(?:four times (?:a|per) day|four times daily|qid|qds)\b/, (p) => (p.timesPerDay = 4)],
  [/\b(?:three times (?:a|per) day|three times daily|thrice daily|thrice a day|tds|tid)\b/, (p) => (p.timesPerDay = 3)],
  [/\b(?:twice (?:a|per) day|twice daily|two times (?:a|per) day|bd|bid)\b/, (p) => (p.timesPerDay = 2)],
  [/\b(?:once (?:a|per) day|once daily|one time (?:a|per) day|od|daily|every day)\b/, (p) => (p.timesPerDay = 1)],
  [/\b(?:before breakfast)\b/, (p) => (p.food = "beforeBreakfast")],
  [/\b(?:on an empty stomach|empty stomach)\b/, (p) => (p.food = "empty")],
  [/\b(?:before (?:food|meals?|eating))\b/, (p) => (p.food = "before")],
  [/\b(?:after (?:food|meals?|eating))\b/, (p) => (p.food = "after")],
  [/\b(?:with (?:food|meals?))\b/, (p) => (p.food = "with")],
  [/\b(?:as needed|when needed|if needed|as required|when required|sos|prn)\b/, (p) => (p.asNeeded = true)],
  [/\b(?:at bedtime|before (?:bed|sleep))\b/, (p) => p.slots.push("bedtime")],
  [/\b(?:in the morning|morning)\b/, (p) => p.slots.push("morning")],
  [/\b(?:in the afternoon|afternoon)\b/, (p) => p.slots.push("afternoon")],
  [/\b(?:at night|in the night|night)\b/, (p) => p.slots.push("night")],
  [/\b(?:for fever)\b/, (p) => (p.purpose = "fever")],
  [/\b(?:for pain)\b/, (p) => (p.purpose = "pain")],
];

/** Words that carry no instruction of their own. */
const FILLER = new Set(["and", "take", "the", "a", "an", "then", "only", "tablet", "tablets", "dose"]);

export function parseFrequency(text: string): Parsed {
  const parsed: Parsed = {
    timesPerDay: null,
    slots: [],
    food: null,
    asNeeded: false,
    maxPerDay: null,
    purpose: null,
    understood: true,
  };
  let rest = ` ${text.toLowerCase().replace(/[.,;:()/-]+/g, " ").replace(/\s+/g, " ").trim()} `;
  for (const [pattern, apply] of PHRASES) {
    const match = rest.match(pattern);
    if (match) {
      apply(parsed, match);
      rest = rest.replace(pattern, " ");
    }
  }
  const leftover = rest.split(" ").filter((w) => w && !FILLER.has(w));
  const saidSomething =
    parsed.timesPerDay !== null || parsed.slots.length > 0 || parsed.asNeeded || parsed.food !== null;
  parsed.understood = leftover.length === 0 && saidSomething;
  return parsed;
}

type Phrasebook = {
  times: Record<1 | 2 | 3 | 4, string>;
  slot: Record<Slot, string>;
  food: Record<Food, string>;
  asNeeded: string;
  maxPerDay: (n: number) => string;
  purpose: Record<Purpose, string>;
  forDays: (n: number) => string;
  notUnderstood: string;
  disclaimer: string;
};

const BOOK: Record<Language, Phrasebook> = {
  en: {
    times: { 1: "Once a day", 2: "Twice a day", 3: "Three times a day", 4: "Four times a day" },
    slot: { morning: "in the morning", afternoon: "in the afternoon", night: "at night", bedtime: "at bedtime" },
    food: {
      before: "before food",
      after: "after food",
      with: "with food",
      empty: "on an empty stomach",
      beforeBreakfast: "before breakfast",
    },
    asNeeded: "only when needed",
    maxPerDay: (n) => `not more than ${n} a day`,
    purpose: { fever: "for fever", pain: "for pain" },
    forDays: (n) => `for ${n} days`,
    notUnderstood: "Ask your pharmacist to explain these instructions.",
    disclaimer: "From your doctor's instructions. If anything is unclear, ask your doctor or pharmacist.",
  },
  hi: {
    times: { 1: "दिन में एक बार", 2: "दिन में दो बार", 3: "दिन में तीन बार", 4: "दिन में चार बार" },
    slot: { morning: "सुबह", afternoon: "दोपहर में", night: "रात को", bedtime: "सोने से पहले" },
    food: {
      before: "खाने से पहले",
      after: "खाने के बाद",
      with: "खाने के साथ",
      empty: "खाली पेट",
      beforeBreakfast: "नाश्ते से पहले",
    },
    asNeeded: "ज़रूरत होने पर ही",
    maxPerDay: (n) => `दिन में ${n} से ज़्यादा नहीं`,
    purpose: { fever: "बुखार के लिए", pain: "दर्द के लिए" },
    forDays: (n) => `${n} दिन तक`,
    notUnderstood: "ये निर्देश समझने के लिए अपने फार्मासिस्ट से पूछें।",
    disclaimer: "आपके डॉक्टर के निर्देशों से। कुछ भी स्पष्ट न हो तो डॉक्टर या फार्मासिस्ट से पूछें।",
  },
  ta: {
    times: {
      1: "ஒரு நாளைக்கு ஒரு முறை",
      2: "ஒரு நாளைக்கு இரண்டு முறை",
      3: "ஒரு நாளைக்கு மூன்று முறை",
      4: "ஒரு நாளைக்கு நான்கு முறை",
    },
    slot: { morning: "காலையில்", afternoon: "மதியம்", night: "இரவில்", bedtime: "தூங்கும் முன்" },
    food: {
      before: "உணவுக்கு முன்",
      after: "உணவுக்குப் பின்",
      with: "உணவுடன்",
      empty: "வெறும் வயிற்றில்",
      beforeBreakfast: "காலை உணவுக்கு முன்",
    },
    asNeeded: "தேவைப்படும்போது மட்டும்",
    maxPerDay: (n) => `ஒரு நாளைக்கு ${n}-க்கு மேல் வேண்டாம்`,
    purpose: { fever: "காய்ச்சலுக்கு", pain: "வலிக்கு" },
    forDays: (n) => `${n} நாட்களுக்கு`,
    notUnderstood: "இந்த வழிமுறைகளை உங்கள் மருந்தாளரிடம் கேட்டுத் தெரிந்துகொள்ளுங்கள்.",
    disclaimer: "உங்கள் மருத்துவரின் வழிமுறைகளிலிருந்து. சந்தேகம் இருந்தால் மருத்துவர் அல்லது மருந்தாளரிடம் கேளுங்கள்.",
  },
  kn: {
    times: { 1: "ದಿನಕ್ಕೆ ಒಂದು ಬಾರಿ", 2: "ದಿನಕ್ಕೆ ಎರಡು ಬಾರಿ", 3: "ದಿನಕ್ಕೆ ಮೂರು ಬಾರಿ", 4: "ದಿನಕ್ಕೆ ನಾಲ್ಕು ಬಾರಿ" },
    slot: { morning: "ಬೆಳಿಗ್ಗೆ", afternoon: "ಮಧ್ಯಾಹ್ನ", night: "ರಾತ್ರಿ", bedtime: "ಮಲಗುವ ಮುನ್ನ" },
    food: {
      before: "ಊಟಕ್ಕೆ ಮೊದಲು",
      after: "ಊಟದ ನಂತರ",
      with: "ಊಟದೊಂದಿಗೆ",
      empty: "ಖಾಲಿ ಹೊಟ್ಟೆಯಲ್ಲಿ",
      beforeBreakfast: "ಬೆಳಗಿನ ತಿಂಡಿಗೆ ಮೊದಲು",
    },
    asNeeded: "ಅಗತ್ಯವಿದ್ದಾಗ ಮಾತ್ರ",
    maxPerDay: (n) => `ದಿನಕ್ಕೆ ${n}ಕ್ಕಿಂತ ಹೆಚ್ಚು ಬೇಡ`,
    purpose: { fever: "ಜ್ವರಕ್ಕೆ", pain: "ನೋವಿಗೆ" },
    forDays: (n) => `${n} ದಿನಗಳವರೆಗೆ`,
    notUnderstood: "ಈ ಸೂಚನೆಗಳನ್ನು ನಿಮ್ಮ ಔಷಧಿಕಾರರಿಂದ ಕೇಳಿ ತಿಳಿಯಿರಿ.",
    disclaimer: "ನಿಮ್ಮ ವೈದ್ಯರ ಸೂಚನೆಗಳಿಂದ. ಏನಾದರೂ ಸ್ಪಷ್ಟವಾಗದಿದ್ದರೆ ವೈದ್ಯರು ಅಥವಾ ಔಷಧಿಕಾರರನ್ನು ಕೇಳಿ.",
  },
  te: {
    times: { 1: "రోజుకు ఒకసారి", 2: "రోజుకు రెండుసార్లు", 3: "రోజుకు మూడుసార్లు", 4: "రోజుకు నాలుగుసార్లు" },
    slot: { morning: "ఉదయం", afternoon: "మధ్యాహ్నం", night: "రాత్రి", bedtime: "పడుకునే ముందు" },
    food: {
      before: "భోజనానికి ముందు",
      after: "భోజనం తర్వాత",
      with: "భోజనంతో పాటు",
      empty: "ఖాళీ కడుపుతో",
      beforeBreakfast: "అల్పాహారానికి ముందు",
    },
    asNeeded: "అవసరమైనప్పుడు మాత్రమే",
    maxPerDay: (n) => `రోజుకు ${n} కంటే ఎక్కువ వద్దు`,
    purpose: { fever: "జ్వరానికి", pain: "నొప్పికి" },
    forDays: (n) => `${n} రోజుల పాటు`,
    notUnderstood: "ఈ సూచనలను మీ ఫార్మసిస్ట్‌ను అడిగి తెలుసుకోండి.",
    disclaimer: "మీ డాక్టర్ సూచనల నుండి. ఏదైనా అర్థం కాకపోతే డాక్టర్ లేదా ఫార్మసిస్ట్‌ను అడగండి.",
  },
  bn: {
    times: { 1: "দিনে একবার", 2: "দিনে দুবার", 3: "দিনে তিনবার", 4: "দিনে চারবার" },
    slot: { morning: "সকালে", afternoon: "দুপুরে", night: "রাতে", bedtime: "ঘুমানোর আগে" },
    food: {
      before: "খাবারের আগে",
      after: "খাবারের পরে",
      with: "খাবারের সাথে",
      empty: "খালি পেটে",
      beforeBreakfast: "সকালের খাবারের আগে",
    },
    asNeeded: "শুধু দরকার হলে",
    maxPerDay: (n) => `দিনে ${n}টির বেশি নয়`,
    purpose: { fever: "জ্বরের জন্য", pain: "ব্যথার জন্য" },
    forDays: (n) => `${n} দিন ধরে`,
    notUnderstood: "এই নির্দেশনা বুঝতে আপনার ফার্মাসিস্টকে জিজ্ঞাসা করুন।",
    disclaimer: "আপনার ডাক্তারের নির্দেশনা থেকে। কিছু অস্পষ্ট হলে ডাক্তার বা ফার্মাসিস্টকে জিজ্ঞাসা করুন।",
  },
};

export interface Instruction {
  /** Short parts, each complete on its own; shown separated, never joined into grammar. */
  parts: string[];
  understood: boolean;
}

/** The instruction for one medicine, in one language. */
export function instruction(frequency: string, durationDays: number, language: Language): Instruction {
  const p = parseFrequency(frequency);
  const book = BOOK[language];
  if (!p.understood) return { parts: [book.notUnderstood], understood: false };

  const parts: string[] = [];
  if (p.asNeeded) parts.push(book.asNeeded);
  if (p.purpose) parts.push(book.purpose[p.purpose]);
  if (p.timesPerDay) parts.push(book.times[p.timesPerDay]);
  p.slots.forEach((s) => parts.push(book.slot[s]));
  if (p.food) parts.push(book.food[p.food]);
  if (p.maxPerDay) parts.push(book.maxPerDay(p.maxPerDay));
  parts.push(book.forDays(durationDays));
  return { parts, understood: true };
}

export function disclaimer(language: Language): string {
  return BOOK[language].disclaimer;
}

/** Common phrases this recognises, offered to the doctor as suggestions. */
export const FREQUENCY_SUGGESTIONS = [
  "Once daily",
  "Once daily, before breakfast",
  "Twice daily after food",
  "Twice daily before food",
  "Three times daily after food",
  "At night",
  "At bedtime",
  "Morning and night after food",
  "As needed for fever, at most 3 a day",
  "As needed for pain, at most 3 a day",
];

/**
 * The message a family member receives. Medicines and how to take them only:
 * the diagnosis stays in the portal, since a forwarded message can go anywhere.
 */
export function shareText(prescription: Prescription, language: Language): string {
  const lines = prescription.items.map((item, i) => {
    const how = instruction(item.frequency, item.durationDays, language);
    const english = language !== "en" || !how.understood ? `\n   (${item.frequency}, ${item.durationDays} days)` : "";
    return `${i + 1}. ${item.medicine} ${item.strength ?? ""} ${item.dosage}\n   ${how.parts.join(" · ")}${english}`;
  });
  return [
    `Medicines from ${prescription.doctorName}, ${formatDate(prescription.issuedAt)}`,
    "",
    ...lines,
    "",
    disclaimer(language),
  ].join("\n");
}
