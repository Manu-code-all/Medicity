import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { stores } from "../../api/endpoints";
import { LocationBar } from "../../components/LocationBar";
import { formatDistance, formatStoreHours, usePosition } from "../../lib/geo";

const RADII = [1000, 3000, 5000];

export function ChemistsPage() {
  const position = usePosition();
  const [radiusM, setRadiusM] = useState(3000);
  const { lat, lng } = position.point;

  const nearby = useQuery({
    queryKey: ["stores", "nearby", lat, lng, radiusM],
    queryFn: () => stores.nearby(lat, lng, radiusM),
  });

  return (
    <div className="stack">
      <header>
        <h1 className="portal__title">Chemists near you</h1>
        <p className="muted">
          Verified neighbourhood stores. Every store here has had its drug licence checked by Medicity.
        </p>
      </header>

      <LocationBar position={position}>
        <label className="inline-field">
          Within
          <select value={radiusM} onChange={(e) => setRadiusM(Number(e.target.value))}>
            {RADII.map((r) => (
              <option key={r} value={r}>
                {r / 1000} km
              </option>
            ))}
          </select>
        </label>
      </LocationBar>

      {nearby.isError && <p className="error">Could not load nearby chemists.</p>}
      {nearby.isPending && <div className="card skeleton" style={{ height: 140 }} />}
      {nearby.data?.length === 0 && (
        <div className="card empty">
          <h2>No chemists within {radiusM / 1000} km</h2>
          <p className="muted">Try a wider radius, or the demo area.</p>
        </div>
      )}

      <ul className="store-list">
        {nearby.data?.map((store) => (
          <li key={store.id} className="card store-card">
            <div className="store-card__head">
              <div>
                <h2>{store.name}</h2>
                <p className="muted">
                  {store.addressLine}, {store.city}
                </p>
              </div>
              <span className="store-card__distance">{formatDistance(store.distanceM)}</span>
            </div>
            <p className="store-card__meta">
              <span className={store.openNow ? "badge badge--completed" : "badge"}>
                {store.openNow ? "Open now" : "Closed now"}
              </span>
              <span>{formatStoreHours(store)}</span>
              <span>Keeps medicines aside for {store.holdHours} hours</span>
              <a href={`tel:${store.phone}`}>{store.phone}</a>
            </p>
          </li>
        ))}
      </ul>
    </div>
  );
}
