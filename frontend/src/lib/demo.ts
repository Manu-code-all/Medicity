/**
 * The public demo: shared accounts (their password is published on the landing
 * page and in the README) and the Indiranagar chemists the seed creates. The
 * landing page draws its map from these, so it shows the demo as it is.
 */

export type LineRole = "patient" | "doctor" | "chemist";

export const DEMO_PASSWORD = "demo-password-2026";

export const DEMO_ACCOUNTS: Record<LineRole, { email: string; name: string; who: string }> = {
  patient: { email: "patient@medicity.demo", name: "Meera Nair", who: "manages her mother and son too" },
  doctor: { email: "dr.rao@medicity.demo", name: "Dr. Anjali Rao", who: "today's schedule is waiting" },
  chemist: { email: "chemist@medicity.demo", name: "Ravi Kumar", who: "of Sri Sai Medicals, Indiranagar" },
};

export const LOGIN_PATH: Record<LineRole, string> = {
  patient: "/login",
  doctor: "/login/doctor",
  chemist: "/login/chemist",
};

/** Where the demo patient asks from. */
export const DEMO_HOME = { lat: 12.9719, lng: 77.6412 };

export type Answer =
  | { kind: "has"; price: string; detail: string }
  | { kind: "partly"; price: string; detail: string }
  | { kind: "waiting" };

export interface MapStore {
  name: string;
  lat: number;
  lng: number;
  distance: string;
  answer: Answer;
  /** Which side of its marker the label sits, so labels do not collide. */
  side: "left" | "right";
}

/** The seed's open question: 14 omeprazole capsules, a cheaper brand allowed. */
export const DEMO_STORES: MapStore[] = [
  { name: "Green Cross Pharmacy", lat: 12.9784, lng: 77.64, distance: "740 m", side: "left",
    answer: { kind: "has", price: "₹77.00", detail: "All 14" } },
  { name: "Lakshmi Medical Stores", lat: 12.966, lng: 77.648, distance: "990 m", side: "right",
    answer: { kind: "has", price: "₹58.80", detail: "Omez, same medicine" } },
  { name: "Nightingale 24x7", lat: 12.959, lng: 77.644, distance: "1.5 km", side: "left",
    answer: { kind: "partly", price: "₹58.00", detail: "10 of 14" } },
  { name: "Sri Sai Medicals", lat: 12.9745, lng: 77.6408, distance: "290 m", side: "right",
    answer: { kind: "waiting" } },
  { name: "CityCare Pharmacy", lat: 12.99, lng: 77.66, distance: "2.9 km", side: "left",
    answer: { kind: "waiting" } },
];

/** Kilometres per degree near Bengaluru's latitude. */
const KM_PER_DEG_LAT = 111.0;
const KM_PER_DEG_LNG = 108.4;

/** Offset from the demo home in kilometres (east and north positive). */
export function offsetKm(lat: number, lng: number) {
  return { x: (lng - DEMO_HOME.lng) * KM_PER_DEG_LNG, y: (lat - DEMO_HOME.lat) * KM_PER_DEG_LAT };
}
