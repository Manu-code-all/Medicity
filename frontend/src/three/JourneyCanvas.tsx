import type { MutableRefObject } from "react";
import { JourneyScene } from "./JourneyScene";
import { Stage } from "./Stage";

/** The journey's canvas, as a default export so the page can load it lazily as one chunk. */
export default function JourneyCanvas({ progress }: { progress: MutableRefObject<number> }) {
  return (
    <Stage
      className="j3__canvas"
      label="A small city with the transit line running through it. Scrolling moves along the line past five stations: book, visit, prescription, chemists nearby and pick up."
      fallback={<div className="j3__flat" />}
      camera={{ position: [0, 8, 10], fov: 46 }}
    >
      <JourneyScene progress={progress} />
    </Stage>
  );
}
