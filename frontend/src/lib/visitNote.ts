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
