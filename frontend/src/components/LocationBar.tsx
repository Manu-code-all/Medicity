import type { usePosition } from "../lib/geo";

type Position = ReturnType<typeof usePosition>;

/** Where a search is centred, and the controls to change it. */
export function LocationBar({ position, children }: { position: Position; children?: React.ReactNode }) {
  const { point, status, locate, resetToDemoArea } = position;
  return (
    <div className="location-bar card">
      <div>
        <p className="eyebrow">Searching near</p>
        <strong>{point.label}</strong>
        {status === "denied" && (
          <p className="error">Location permission was refused; still searching the area above.</p>
        )}
        {status === "unavailable" && <p className="error">This browser could not find your location.</p>}
      </div>
      <div className="location-bar__actions">
        {children}
        <button type="button" className="button--ghost" onClick={locate} disabled={status === "locating"}>
          {status === "locating" ? "Locating…" : "Use my location"}
        </button>
        {point.label === "Your location" && (
          <button type="button" className="link" onClick={resetToDemoArea}>
            Use the demo area
          </button>
        )}
      </div>
    </div>
  );
}
