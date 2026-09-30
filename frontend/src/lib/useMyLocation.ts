import { useCallback, useContext, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { portal } from "../api/endpoints";
import { AuthContext } from "../auth/context";
import type { Point } from "./geo";

type Status = "idle" | "locating" | "refused";

/**
 * Where to measure distances from. A patient who saved their location (at
 * sign-up or on the profile) has it with no question asked; anyone else gets
 * `locate()`, which asks the browser when a person presses a button, never on
 * arrival. Returns null for `point` until one of the two answers.
 */
export function useMyLocation() {
  const session = useContext(AuthContext)?.session ?? null;
  const profile = useQuery({
    queryKey: ["portal", "profile"],
    queryFn: portal.profile,
    enabled: session?.role === "PATIENT",
    staleTime: 5 * 60_000,
  });
  const [browser, setBrowser] = useState<Point | null>(null);
  const [status, setStatus] = useState<Status>("idle");

  const locate = useCallback(() => {
    if (!("geolocation" in navigator)) {
      setStatus("refused");
      return;
    }
    setStatus("locating");
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        setBrowser({ lat: pos.coords.latitude, lng: pos.coords.longitude });
        setStatus("idle");
      },
      () => setStatus("refused"),
      { enableHighAccuracy: false, timeout: 10_000, maximumAge: 300_000 },
    );
  }, []);

  const saved =
    profile.data?.homeLatitude != null && profile.data.homeLongitude != null
      ? { lat: profile.data.homeLatitude, lng: profile.data.homeLongitude }
      : null;

  return { point: browser ?? saved, source: browser ? ("browser" as const) : saved ? ("saved" as const) : null, status, locate };
}
