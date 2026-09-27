import { useCallback, useState } from "react";
import type { StoreHoursInfo } from "../api/types";

export interface Point {
  lat: number;
  lng: number;
}

/**
 * Indiranagar, Bengaluru: where the demo patient and the demo chemists are.
 * Used until the visitor shares their own location, so the demo works for
 * someone browsing from anywhere.
 */
export const DEMO_AREA: Point & { label: string } = {
  lat: 12.9719,
  lng: 77.6412,
  label: "Indiranagar, Bengaluru (demo area)",
};

/** "350 m", "2.4 km". Rounded: a store's door is not a precise point. */
export function formatDistance(metres: number): string {
  if (metres < 1000) return `${Math.max(10, Math.round(metres / 10) * 10)} m`;
  return `${(metres / 1000).toFixed(1)} km`;
}

/** "08:00–22:00" or "Open 24 hours". The API sends local "HH:mm:ss". */
export function formatStoreHours(store: Pick<StoreHoursInfo, "opensAt" | "closesAt" | "open24h">): string {
  if (store.open24h) return "Open 24 hours";
  return `${store.opensAt.slice(0, 5)}–${store.closesAt.slice(0, 5)}`;
}

type Status = "idle" | "locating" | "denied" | "unavailable";

/**
 * The point to search around: the demo area until the visitor asks for their
 * own position. Asking is a button, never automatic, because the browser's
 * permission prompt should follow something the person did.
 */
export function usePosition() {
  const [point, setPoint] = useState<Point & { label: string }>(DEMO_AREA);
  const [status, setStatus] = useState<Status>("idle");

  const locate = useCallback(() => {
    if (!("geolocation" in navigator)) {
      setStatus("unavailable");
      return;
    }
    setStatus("locating");
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        setPoint({ lat: pos.coords.latitude, lng: pos.coords.longitude, label: "Your location" });
        setStatus("idle");
      },
      (err) => setStatus(err.code === err.PERMISSION_DENIED ? "denied" : "unavailable"),
      { enableHighAccuracy: false, timeout: 10_000, maximumAge: 300_000 },
    );
  }, []);

  const resetToDemoArea = useCallback(() => {
    setPoint(DEMO_AREA);
    setStatus("idle");
  }, []);

  return { point, status, locate, resetToDemoArea };
}
