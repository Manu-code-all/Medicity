import type { Intake } from "../api/types";

const KEY = "medicity.visitNote";

/**
 * What the visitor described in the body guide, kept for this tab only so the
 * booking form can start from it, across the sign-in in between. Session
 * storage can be missing or refuse (private windows), so both sides fail quietly.
 */
export function saveVisitNote(text: string): void {
  try {
    if (text.trim()) sessionStorage.setItem(KEY, text.trim());
    else sessionStorage.removeItem(KEY);
  } catch {
    // Not remembered; the booking form starts empty instead.
  }
}

export function readVisitNote(): string {
  try {
    return sessionStorage.getItem(KEY) ?? "";
  } catch {
    return "";
  }
}

const INTAKE_KEY = "medicity.intake";

/** The body guide's answers, kept for this tab so the booking form can offer to share them. */
export function saveIntake(intake: Intake | null): void {
  try {
    if (intake) sessionStorage.setItem(INTAKE_KEY, JSON.stringify(intake));
    else sessionStorage.removeItem(INTAKE_KEY);
  } catch {
    // Not remembered; the booking simply has no intake.
  }
}

export function readIntake(): Intake | null {
  try {
    const raw = sessionStorage.getItem(INTAKE_KEY);
    return raw ? (JSON.parse(raw) as Intake) : null;
  } catch {
    return null;
  }
}
