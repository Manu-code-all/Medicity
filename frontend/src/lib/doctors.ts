/**
 * What a doctor can register as. The same closed list as the server's
 * DoctorSignUpService.SPECIALITIES, so the directory filter and the body
 * guide, which name these exactly, stay in step.
 */
export const SPECIALITIES = [
  "Cardiology",
  "Dentistry",
  "Dermatology",
  "ENT",
  "Gastroenterology",
  "General Medicine",
  "General Surgery",
  "Gynaecology",
  "Nephrology",
  "Neurology",
  "Orthopaedics",
  "Paediatrics",
  "Pulmonology",
  "Urology",
];

/** Where Indian doctors hold their registration; "Other" is typed in. */
export const MEDICAL_COUNCILS = [
  "National Medical Commission",
  "Karnataka Medical Council",
  "Maharashtra Medical Council",
  "Tamil Nadu Medical Council",
  "Delhi Medical Council",
  "Kerala State Medical Council",
  "Telangana State Medical Council",
  "Dental Council of India",
];

export const WEEKDAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];

export const SLOT_LENGTHS = [10, 15, 20, 30, 45, 60];

export interface HoursRow {
  on: boolean;
  startsAt: string;
  endsAt: string;
  slotMinutes: number;
}

function minutes(t: string) {
  const [h = 0, m = 0] = t.split(":").map(Number);
  return h * 60 + m;
}

/** How many appointments a day holds with these settings. */
export function slotsInDay(row: HoursRow) {
  if (!row.on) return 0;
  const length = minutes(row.endsAt) - minutes(row.startsAt);
  return length > 0 ? Math.floor(length / row.slotMinutes) : 0;
}

