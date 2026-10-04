import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ApiError } from "../../api/client";
import { pharmacies } from "../../api/endpoints";
import type { NearbyPharmacy } from "../../api/types";
import {
  CITY_CENTRES,
  LOCATION_FAILURE_MESSAGE,
  LocationError,
  currentPoint,
  roundPoint,
  type Point,
} from "../../lib/location";

const RADIUS_OPTIONS = [2, 5, 10, 20];

interface Origin extends Point {
  /** How the point was chosen, shown above the results. */
  label: string;
}

export function PharmaciesPage() {
  const [origin, setOrigin] = useState<Origin | null>(null);
  const [radiusKm, setRadiusKm] = useState(5);
  const [locating, setLocating] = useState(false);
  const [locateError, setLocateError] = useState<string | null>(null);

  const results = useQuery({
    // The key carries the point, so moving or widening the radius refetches and
    // going back to a place already searched is answered from cache.
    queryKey: ["pharmacies", "nearby", origin?.lat, origin?.lng, radiusKm],
    queryFn: () => pharmacies.nearby(origin!.lat, origin!.lng, radiusKm),
    enabled: origin !== null,
    staleTime: 60_000,
  });

  async function useMyLocation() {
    setLocating(true);
    setLocateError(null);
    try {
      const point = roundPoint(await currentPoint());
      setOrigin({ ...point, label: "your location" });
    } catch (error) {
      setLocateError(
        LOCATION_FAILURE_MESSAGE[error instanceof LocationError ? error.reason : "unavailable"],
      );
    } finally {
      setLocating(false);
    }
  }

  function pickCity(name: string) {
    const city = CITY_CENTRES.find((c) => c.name === name);
    if (city) {
      setLocateError(null);
      setOrigin({ lat: city.lat, lng: city.lng, label: `the centre of ${city.name}` });
    }
  }

  return (
    <div className="stack">
      <header>
        <h1 className="portal__title">Pharmacies near you</h1>
        <p className="muted">Find a chemist close by. Call ahead to check they have your medicines.</p>
      </header>

      <section className="card stack" aria-label="Where to search">
        <div className="actions">
          <button type="button" className="button" onClick={useMyLocation} disabled={locating}>
            {locating ? "Finding you…" : "Use my location"}
          </button>
          <label htmlFor="city" className="sr-only">
            Or pick a city
          </label>
          <select id="city" value="" onChange={(e) => pickCity(e.target.value)}>
            <option value="">Or pick a city…</option>
            {CITY_CENTRES.map((c) => (
              <option key={c.name} value={c.name}>
                {c.name}
              </option>
            ))}
          </select>
          <label htmlFor="radius" className="sr-only">
            Search distance
          </label>
          <select id="radius" value={radiusKm} onChange={(e) => setRadiusKm(Number(e.target.value))}>
            {RADIUS_OPTIONS.map((km) => (
              <option key={km} value={km}>
                Within {km} km
              </option>
            ))}
          </select>
        </div>
        {locateError && (
          <p className="error" role="alert">
            {locateError}
          </p>
        )}
      </section>

      {origin === null && !locateError && (
        <p className="muted">Share your location, or pick a city, to see pharmacies around it.</p>
      )}

      {origin !== null && results.isPending && <div className="card skeleton" style={{ height: 120 }} />}
      {results.isError && <p className="error">{messageFor(results.error)}</p>}

      {origin !== null && results.data && (
        <>
          <p className="muted" aria-live="polite">
            {results.data.length === 0
              ? `No pharmacies within ${radiusKm} km of ${origin.label}. Try a wider distance.`
              : `${results.data.length} ${results.data.length === 1 ? "pharmacy" : "pharmacies"} within ${radiusKm} km of ${origin.label}, nearest first.`}
          </p>
          <ul className="doctor-list stack">
            {results.data.map((shop) => (
              <PharmacyCard key={shop.id} shop={shop} />
            ))}
          </ul>
        </>
      )}
    </div>
  );
}

function PharmacyCard({ shop }: { shop: NearbyPharmacy }) {
  const address = [shop.addressLine, shop.city, shop.pincode].filter(Boolean).join(", ");
  return (
    <li className="card doctor">
      <div>
        <h2>{shop.name}</h2>
        <p>{address}</p>
        <p className="muted">
          {formatDistance(shop.distanceKm)} away · {shop.opensAt}–{shop.closesAt}{" "}
          <span className={shop.openNow ? "badge badge--completed" : "badge badge--cancelled"}>
            {shop.openNow ? "Open now" : "Closed now"}
          </span>
        </p>
      </div>
      <div className="doctor__action">
        <a className="button button--ghost button--sm" href={`tel:${shop.phone}`}>
          Call
        </a>
        <a
          className="button button--sm"
          href={`https://www.google.com/maps/dir/?api=1&destination=${shop.latitude},${shop.longitude}`}
          target="_blank"
          rel="noopener noreferrer"
        >
          Directions
        </a>
      </div>
    </li>
  );
}

/** Under a kilometre in metres, rounded to 10 m, so "0.4 km" reads as "400 m". */
function formatDistance(km: number): string {
  const metres = Math.round(km * 100) * 10;
  // 0.996 km rounds to 1000 m; "1000 m" next to "1.0 km" would read as a mistake.
  return metres < 1000 ? `${metres} m` : `${km.toFixed(1)} km`;
}

function messageFor(error: unknown): string {
  if (error instanceof ApiError && error.status === 400) return "That search was not valid. Try another place.";
  return "Could not load pharmacies. Please try again.";
}
