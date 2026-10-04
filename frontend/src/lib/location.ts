/**
 * Where the user is, from the browser. The position is used for one request and
 * kept in component state only; it is never written to storage.
 */

export interface Point {
  lat: number;
  lng: number;
}

export type LocationFailure = "unsupported" | "denied" | "unavailable";

export class LocationError extends Error {
  readonly reason: LocationFailure;

  constructor(reason: LocationFailure) {
    super(reason);
    this.name = "LocationError";
    this.reason = reason;
  }
}

/** What to tell the user for each way locating can fail. */
export const LOCATION_FAILURE_MESSAGE: Record<LocationFailure, string> = {
  unsupported: "This browser cannot share its location. Pick a city below instead.",
  denied: "Location access was blocked. Allow it in your browser's site settings, or pick a city below.",
  unavailable: "Your location could not be found just now. Try again, or pick a city below.",
};

export function currentPoint(): Promise<Point> {
  return new Promise((resolve, reject) => {
    if (!("geolocation" in navigator)) {
      reject(new LocationError("unsupported"));
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (position) => resolve({ lat: position.coords.latitude, lng: position.coords.longitude }),
      (error) => reject(new LocationError(error.code === error.PERMISSION_DENIED ? "denied" : "unavailable")),
      // A coarse fix from the network is plenty for finding a chemist, and is
      // much faster than waiting for GPS indoors. Ten minutes old is fine too.
      { enableHighAccuracy: false, timeout: 10_000, maximumAge: 600_000 },
    );
  });
}

/**
 * Rounded to 4 decimal places (about 11 m). That is finer than any distance a
 * patient cares about, and it means a few metres of GPS jitter does not count
 * as a new place and refetch the list.
 */
export function roundPoint(point: Point): Point {
  const round = (n: number) => Math.round(n * 10_000) / 10_000;
  return { lat: round(point.lat), lng: round(point.lng) };
}

/**
 * Approximate city centres, for when the browser cannot or will not share a
 * location (a desktop without GPS, a denied permission). Good to a few km,
 * which is why the page labels the result as around the centre.
 */
export const CITY_CENTRES: ReadonlyArray<{ name: string } & Point> = [
  { name: "Bengaluru", lat: 12.9716, lng: 77.5946 },
  { name: "Chennai", lat: 13.0827, lng: 80.2707 },
  { name: "Delhi", lat: 28.6139, lng: 77.209 },
  { name: "Hyderabad", lat: 17.385, lng: 78.4867 },
  { name: "Kolkata", lat: 22.5726, lng: 88.3639 },
  { name: "Mumbai", lat: 19.076, lng: 72.8777 },
  { name: "Pune", lat: 18.5204, lng: 73.8567 },
];
