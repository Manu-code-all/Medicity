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
  specialty: string;
  alternative: string;
  rationale: string;
}

/**
 * What to do, from the area and the symptoms ticked. Any warning sign wins
 * outright. Otherwise the first ticked symptom that names a speciality
 * decides, and the area's default applies when none does.
 */
export function recommend(regionId: string, symptomIds: string[]): Recommendation {
  const region = BODY_TAXONOMY[regionId];
  if (!region) throw new Error(`Unknown body region ${regionId}`);
  const chosen = region.subSymptoms.filter((s) => symptomIds.includes(s.id));
  const emergency = chosen.some((s) => s.isRedFlag);
  const specific = chosen.find((s) => s.specialty)?.specialty;
  const specialty = specific ?? region.routing.primarySpecialty;
  const alternative =
    specialty === region.routing.primarySpecialty ? region.routing.secondarySpecialty : region.routing.primarySpecialty;
  return { emergency, specialty, alternative, rationale: region.routing.rationale };
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
