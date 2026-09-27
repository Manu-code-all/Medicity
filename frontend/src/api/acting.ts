import { useSyncExternalStore } from "react";

/**
 * Which family member the signed-in account is acting for: null for the
 * account holder themselves.
 *
 * Sent as the X-Patient-Id header on patient requests. The server checks it
 * against the account's own family on every request; this value only
 * chooses, it never grants anything.
 */
const KEY = "medicity.actingFor";
const listeners = new Set<() => void>();

function read(): string | null {
  try {
    return sessionStorage.getItem(KEY);
  } catch {
    return null;
  }
}

let current: string | null = read();

export const actingFor = {
  get: () => current,

  set(patientId: string | null) {
    current = patientId;
    try {
      if (patientId) sessionStorage.setItem(KEY, patientId);
      else sessionStorage.removeItem(KEY);
    } catch {
      // Private mode: the choice lasts until reload, which is fine.
    }
    listeners.forEach((l) => l());
  },

  subscribe(listener: () => void) {
    listeners.add(listener);
    return () => listeners.delete(listener);
  },
};

/** Requests that act "as the patient": the portal, bookings, questions to chemists. */
export function actsAsPatient(path: string): boolean {
  return (
    (path.startsWith("/api/v1/patients/me") && !path.startsWith("/api/v1/patients/me/family")) ||
    path.startsWith("/api/v1/appointments")
  );
}

export function useActingFor(): string | null {
  return useSyncExternalStore(actingFor.subscribe, actingFor.get, actingFor.get);
}
