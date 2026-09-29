/**
 * Which kind of doctor to see for discomfort in each area of the body.
 *
 * Static and deterministic on purpose: the same taps always give the same
 * answer, instantly and offline, and nothing is generated. It suggests a
 * speciality, never a diagnosis. It was written without a clinician's review
 * (see Known gaps in the engineering log) and must be reviewed before real
 * patients rely on it; the warning signs err towards sending people to
 * emergency care.
 *
 * Specialities are spelt exactly as doctors' specialisations are stored, so a
 * recommendation is also a working search filter.
 */

export type BodyView = "front" | "back";

export interface SubSymptom {
  id: string;
  label: string;
  /** Needs emergency care now: replaces the doctor recommendation. */
  isRedFlag?: boolean;
  /** When this symptom points somewhere more specific than the area's default. */
  specialty?: string;
}

export interface BodyRegion {
  id: string;
  label: string;
  /** Views the area can be tapped in. */
  views: BodyView[];
  subSymptoms: SubSymptom[];
  routing: {
    primarySpecialty: string;
    secondarySpecialty: string;
    rationale: string;
  };
}

/** What the recommendation calls the doctor ("see a gastroenterologist"). */
export const SPECIALIST: Record<string, string> = {
  "General Medicine": "general physician",
  Gastroenterology: "gastroenterologist",
  "General Surgery": "general surgeon",
  Orthopaedics: "orthopaedic doctor",
  ENT: "ENT specialist",
  Neurology: "neurologist",
  Dentistry: "dentist",
  Cardiology: "cardiologist",
  Pulmonology: "chest physician",
  Urology: "urologist",
  Gynaecology: "gynaecologist",
  Nephrology: "kidney specialist",
  Dermatology: "dermatologist",
  Paediatrics: "paediatrician",
};

export const BODY_TAXONOMY: Record<string, BodyRegion> = {
  head_face: {
    id: "head_face",
    label: "Head and face",
    views: ["front", "back"],
    subSymptoms: [
      { id: "headache", label: "Headache that keeps coming back", specialty: "Neurology" },
      { id: "sinus", label: "Blocked nose or sinus pressure", specialty: "ENT" },
      { id: "ear", label: "Ear pain or reduced hearing", specialty: "ENT" },
      { id: "tooth", label: "Toothache or jaw pain", specialty: "Dentistry" },
      { id: "worst_headache", label: "Sudden, severe headache, the worst ever", isRedFlag: true },
      { id: "face_droop", label: "Face drooping, slurred speech or a weak arm", isRedFlag: true },
    ],
    routing: {
      primarySpecialty: "Neurology",
      secondarySpecialty: "General Medicine",
      rationale: "Headaches that keep returning are assessed by a neurologist; ear, nose and teeth have their own specialists.",
    },
  },
  neck_throat: {
    id: "neck_throat",
    label: "Neck and throat",
    views: ["front"],
    subSymptoms: [
      { id: "sore_throat", label: "Sore throat or hoarse voice", specialty: "ENT" },
      { id: "neck_swelling", label: "Swelling or a lump in the neck" },
      { id: "swallowing", label: "Discomfort when swallowing", specialty: "ENT" },
      { id: "cannot_breathe", label: "Struggling to breathe or to swallow saliva", isRedFlag: true },
    ],
    routing: {
      primarySpecialty: "ENT",
      secondarySpecialty: "General Medicine",
      rationale: "Throat and voice problems are examined by an ENT specialist; a general physician can check neck swellings and the thyroid first.",
    },
  },
  chest: {
    id: "chest",
    label: "Chest",
    views: ["front"],
    subSymptoms: [
      { id: "cough", label: "Cough lasting more than two weeks", specialty: "Pulmonology" },
      { id: "breathless_walking", label: "Breathless when walking or climbing stairs", specialty: "Pulmonology" },
      { id: "palpitations", label: "Heart racing or skipping beats", specialty: "Cardiology" },
      { id: "exertion_pain", label: "Chest discomfort on exertion that settles with rest", specialty: "Cardiology" },
      { id: "crushing_pain", label: "Crushing chest pressure spreading to the arm or jaw", isRedFlag: true },
      { id: "breathless_rest", label: "Very breathless at rest, or blue lips", isRedFlag: true },
    ],
    routing: {
      primarySpecialty: "Cardiology",
      secondarySpecialty: "Pulmonology",
      rationale: "Chest symptoms come from the heart or the lungs; a cardiologist or chest physician can tell which.",
    },
  },
  abdomen_upper: {
    id: "abdomen_upper",
    label: "Upper abdomen and stomach",
    views: ["front"],
    subSymptoms: [
      { id: "burning_reflux", label: "Burning sensation or acid reflux" },
      { id: "sharp_cramps", label: "Sharp cramps after meals" },
      { id: "bloat_nausea", label: "Bloating with nausea" },
      { id: "black_stools", label: "Black stools, or vomiting blood", isRedFlag: true },
    ],
    routing: {
      primarySpecialty: "Gastroenterology",
      secondarySpecialty: "General Medicine",
      rationale: "Acidity, reflux or cramps after meals point to the stomach and gut, which a gastroenterologist assesses.",
    },
  },
  abdomen_lower: {
    id: "abdomen_lower",
    label: "Lower abdomen",
    views: ["front"],
    subSymptoms: [
      { id: "bowel_change", label: "Constipation, loose motions or a change in habit" },
      { id: "lower_cramps", label: "Cramping pain that comes and goes" },
      { id: "right_lower", label: "Pain low on the right side", specialty: "General Surgery" },
      { id: "rigid_belly", label: "Severe pain with fever and a hard, tender belly", isRedFlag: true },
    ],
    routing: {
      primarySpecialty: "Gastroenterology",
      secondarySpecialty: "General Surgery",
      rationale: "Bowel symptoms are for a gastroenterologist; pain in one spot, such as low on the right, may need a surgeon's opinion.",
    },
  },
  pelvis_groin: {
    id: "pelvis_groin",
    label: "Pelvis and groin",
    views: ["front"],
    subSymptoms: [
      { id: "burning_urine", label: "Burning or frequent urination", specialty: "Urology" },
      { id: "periods", label: "Painful, heavy or irregular periods", specialty: "Gynaecology" },
      { id: "groin_lump", label: "A lump or swelling in the groin", specialty: "General Surgery" },
      { id: "pregnancy_pain", label: "Severe pain or bleeding during pregnancy", isRedFlag: true },
    ],
    routing: {
      primarySpecialty: "Urology",
      secondarySpecialty: "Gynaecology",
      rationale: "Urinary symptoms are for a urologist and women's health for a gynaecologist; the symptom you choose decides which.",
    },
  },
  joints_upper: {
    id: "joints_upper",
    label: "Shoulders, arms and hands",
    views: ["front", "back"],
    subSymptoms: [
      { id: "shoulder", label: "Shoulder pain or stiffness" },
      { id: "elbow_wrist", label: "Elbow, wrist or hand pain" },
      { id: "many_joints", label: "Several swollen joints at once", specialty: "General Medicine" },
      { id: "deformed", label: "A limb out of shape after a fall", isRedFlag: true },
    ],
    routing: {
      primarySpecialty: "Orthopaedics",
      secondarySpecialty: "General Medicine",
      rationale: "Joint and muscle pain in the arms is assessed by an orthopaedic doctor.",
    },
  },
  joints_lower: {
    id: "joints_lower",
    label: "Hips, knees and feet",
    views: ["front", "back"],
    subSymptoms: [
      { id: "knee", label: "Knee pain or swelling" },
      { id: "ankle", label: "Ankle sprain or heel pain" },
      { id: "hip", label: "Hip pain when walking" },
      { id: "calf_swelling", label: "One calf suddenly swollen, red and painful", isRedFlag: true },
    ],
    routing: {
      primarySpecialty: "Orthopaedics",
      secondarySpecialty: "General Medicine",
      rationale: "Pain in the hips, knees or feet is assessed by an orthopaedic doctor.",
    },
  },
  cervical_spine: {
    id: "cervical_spine",
    label: "Back of the neck and shoulders",
    views: ["back"],
    subSymptoms: [
      { id: "stiff_neck", label: "Stiff, aching neck" },
      { id: "arm_radiating", label: "Pain or tingling running into an arm" },
      { id: "neck_fever", label: "Stiff neck with high fever", isRedFlag: true },
    ],
    routing: {
      primarySpecialty: "Orthopaedics",
      secondarySpecialty: "General Medicine",
      rationale: "Neck pain, and pain that travels into the arm, are assessed by an orthopaedic doctor.",
    },
  },
  thoracic_lumbar: {
    id: "thoracic_lumbar",
    label: "Mid and lower back",
    views: ["back"],
    subSymptoms: [
      { id: "movement_pain", label: "Pain when bending or sitting" },
      { id: "numb_leg", label: "Pain or numbness running down a leg" },
      { id: "stiffness", label: "Stiffness in the morning" },
      { id: "bladder_control", label: "Numb groin, or losing bladder or bowel control", isRedFlag: true },
    ],
    routing: {
      primarySpecialty: "Orthopaedics",
      secondarySpecialty: "General Medicine",
      rationale: "Back pain that changes with movement is assessed by an orthopaedic or spine doctor.",
    },
  },
  flanks: {
    id: "flanks",
    label: "Sides of the back (kidneys)",
    views: ["back"],
    subSymptoms: [
      { id: "colicky", label: "Pain on one side that spreads to the groin", specialty: "Urology" },
      { id: "swelling_feet", label: "Swollen feet or passing less urine", specialty: "Nephrology" },
      { id: "fever_chills", label: "Side pain with high fever and shivering", isRedFlag: true },
    ],
    routing: {
      primarySpecialty: "Urology",
      secondarySpecialty: "Nephrology",
      rationale: "Pain in the side of the back often comes from the kidneys: stones are for a urologist, kidney function for a nephrologist.",
    },
  },
};

export const DURATIONS = [
  { id: "today", label: "Started today" },
  { id: "days", label: "A few days" },
  { id: "weeks", label: "Weeks or longer" },
] as const;

export type DurationId = (typeof DURATIONS)[number]["id"];

export interface Recommendation {
  emergency: boolean;
  /** The visitor wrote about harming themselves: the emergency answer adds the mental health helpline. */
  crisis: boolean;
  specialty: string;
  alternative: string;
  rationale: string;
}

/**
 * Warning signs as people write them. Deliberately broad and blind to
 * negation ("no crushing pain" still matches): a false alarm costs a call,
 * a missed one costs far more. The same rule as the ticked warning signs.
 */
const EMERGENCY_WORDS: RegExp[] = [
  /\b(can'?t|cannot|can not|unable to|not able to|struggling to) breathe?\b/,
  /\b(unconscious|fainted|passed out|not waking up|collapsed)\b/,
  /\b(seizures?|convulsions?|fitting)\b/,
  /\bslurred\b|\b(face|mouth)\b.{0,12}\bdroop|\bdroop\w* (face|mouth)\b/,
  /\bparaly[sz]ed\b|\bcan'?t move (my|one) (arm|leg|side)\b/,
  /\b(vomit\w*|cough\w*) (up )?blood\b|\bblood\b.{0,12}\b(vomit|cough)/,
  /\bcrushing\b|\bspread\w* to (my |the )?(left )?(arm|jaw)\b/,
  /\bworst headache\b|\bbleeding (heavily|a lot)\b|\bwon'?t stop bleeding\b/,
];

const CRISIS_WORDS = /\b(suicid\w*|kill myself|end my life|want to die|harm myself|hurt myself)\b/;

export interface DescriptionReading {
  emergency: boolean;
  crisis: boolean;
  /** The everyday word that named a speciality ("tooth"), if the text has one. */
  word?: string;
  specialty?: string;
}

/**
 * Reads what the visitor typed in their own words, with fixed tables like
 * everything else here: warning signs first, then the earliest everyday
 * word that names a speciality. Nothing is sent anywhere.
 */
export function readDescription(text: string): DescriptionReading {
  const t = text.toLowerCase().replace(/’/g, "'");
  const crisis = CRISIS_WORDS.test(t);
  const emergency = crisis || EMERGENCY_WORDS.some((re) => re.test(t));
  let first: { at: number; word: string } | undefined;
  for (const word of Object.keys(EVERYDAY)) {
    const at = t.search(new RegExp(`\\b${word}(s|es)?\\b`));
    if (at >= 0 && (!first || at < first.at)) first = { at, word };
  }
  const specialty = first && EVERYDAY[first.word];
  return first && specialty ? { emergency, crisis, word: first.word, specialty } : { emergency, crisis };
}

/**
 * What to do, from the area, the symptoms ticked and anything typed. Any
 * warning sign, ticked or typed, wins outright. Otherwise the first ticked
 * symptom that names a speciality decides. Ticks are about the area tapped,
 * so an everyday word in the description decides only when nothing is
 * ticked; the area's default applies when neither does.
 */
export function recommend(regionId: string, symptomIds: string[], description = ""): Recommendation {
  const region = BODY_TAXONOMY[regionId];
  if (!region) throw new Error(`Unknown body region ${regionId}`);
  const chosen = region.subSymptoms.filter((s) => symptomIds.includes(s.id));
  const typed = readDescription(description);
  const emergency = typed.emergency || chosen.some((s) => s.isRedFlag);
  const ticked = chosen.find((s) => s.specialty)?.specialty;
  const worded = chosen.length === 0 ? typed.specialty : undefined;
  const specialty = ticked ?? worded ?? region.routing.primarySpecialty;
  const alternative =
    specialty === region.routing.primarySpecialty ? region.routing.secondarySpecialty : region.routing.primarySpecialty;
  const rationale =
    worded && worded !== region.routing.primarySpecialty
      ? `You mentioned “${typed.word}”, which ${specialistPhrase(worded)} looks after.`
      : region.routing.rationale;
  return { emergency, crisis: typed.crisis, specialty, alternative, rationale };
}

/** "a gastroenterologist", "an ENT specialist" */
export function specialistPhrase(specialty: string): string {
  const who = SPECIALIST[specialty] ?? `${specialty} doctor`;
  return `${/^[aeiouAEIOU]|^ENT/.test(who) ? "an" : "a"} ${who}`;
}

/** The areas tappable in a view, in reading order. */
export function regionsIn(view: BodyView): BodyRegion[] {
  return Object.values(BODY_TAXONOMY).filter((r) => r.views.includes(view));
}

/** Everyday words people type for a speciality, beyond what the body guide lists. */
const EVERYDAY: Record<string, string> = {
  skin: "Dermatology",
  rash: "Dermatology",
  acne: "Dermatology",
  hair: "Dermatology",
  tooth: "Dentistry",
  teeth: "Dentistry",
  gum: "Dentistry",
  heart: "Cardiology",
  "blood pressure": "Cardiology",
  child: "Paediatrics",
  baby: "Paediatrics",
  fever: "General Medicine",
  diabetes: "General Medicine",
  sugar: "General Medicine",
  bone: "Orthopaedics",
  joint: "Orthopaedics",
  stomach: "Gastroenterology",
  acidity: "Gastroenterology",
  kidney: "Nephrology",
  urine: "Urology",
  pregnancy: "Gynaecology",
  period: "Gynaecology",
  ear: "ENT",
  nose: "ENT",
  throat: "ENT",
  cough: "Pulmonology",
  asthma: "Pulmonology",
};

export interface WordMatch {
  /** What matched, as the visitor would say it. */
  label: string;
  specialty: string;
  zone?: string;
}

/**
 * Specialities for everyday words ("knee", "skin", "toothache"): the body
 * guide's areas and symptoms, then a short list of common words. The search
 * box shows these beside doctors and specialities, so typing what hurts
 * works as well as typing a speciality's name.
 */
export function wordMatches(term: string, limit = 3): WordMatch[] {
  const t = term.trim().toLowerCase();
  if (t.length < 3) return [];
  const found: WordMatch[] = [];
  const seen = new Set<string>();
  const add = (m: WordMatch) => {
    const key = `${m.specialty}|${m.label}`;
    if (!seen.has(key) && !found.some((f) => f.specialty === m.specialty)) {
      seen.add(key);
      found.push(m);
    }
  };
  for (const region of Object.values(BODY_TAXONOMY)) {
    for (const s of region.subSymptoms) {
      if (!s.isRedFlag && s.label.toLowerCase().includes(t)) {
        add({ label: s.label, specialty: s.specialty ?? region.routing.primarySpecialty, zone: region.id });
      }
    }
    if (region.label.toLowerCase().includes(t)) {
      add({ label: region.label, specialty: region.routing.primarySpecialty, zone: region.id });
    }
  }
  for (const [word, specialty] of Object.entries(EVERYDAY)) {
    if (word.startsWith(t) || t.startsWith(word)) add({ label: word[0]!.toUpperCase() + word.slice(1), specialty });
  }
  return found.slice(0, limit);
}
