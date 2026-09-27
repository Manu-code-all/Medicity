import type { KeyboardEvent } from "react";
import { SILHOUETTES } from "./silhouettes";
import { BODY_TAXONOMY, type BodyView } from "./taxonomy";

/**
 * The tappable body. Every area is a button: reachable with Tab, chosen with
 * Enter or Space, and named for screen readers.
 */
export function BodyMapCanvas({
  view,
  onViewChange,
  selected,
  onSelect,
}: {
  view: BodyView;
  onViewChange: (view: BodyView) => void;
  selected: string | null;
  onSelect: (regionId: string) => void;
}) {
  const shapes = SILHOUETTES[view];
  const pin = shapes.find((s) => s.id === selected)?.pin;

  function onKey(e: KeyboardEvent, id: string) {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      onSelect(id);
    }
  }

  return (
    <div className="bm-canvas">
      <div className="bm-views" role="group" aria-label="Body view">
        {(["front", "back"] as const).map((v) => (
          <button key={v} type="button" aria-pressed={view === v} onClick={() => onViewChange(v)}>
            {v === "front" ? "Front" : "Back"}
          </button>
        ))}
      </div>
      <svg viewBox="0 0 200 440" className="bm-figure" aria-label={`Body, ${view} view. Choose where it hurts.`}>
        {shapes.map((shape) => (
          <path
            key={shape.id}
            d={shape.d}
            className="bm-region"
            role="button"
            tabIndex={0}
            aria-label={BODY_TAXONOMY[shape.id]?.label}
            aria-pressed={selected === shape.id}
            onClick={() => onSelect(shape.id)}
            onKeyDown={(e) => onKey(e, shape.id)}
          />
        ))}
        {pin && (
          <g className="bm-pin" transform={`translate(${pin[0]} ${pin[1]})`} aria-hidden="true">
            <circle r="9" className="bm-pin__ring" />
            <circle r="4" className="bm-pin__dot" />
          </g>
        )}
      </svg>
    </div>
  );
}
