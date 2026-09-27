import { useState } from "react";
import { BodyMapCanvas } from "./BodyMapCanvas";
import { ClinicalRecommendationCard } from "./ClinicalRecommendationCard";
import { SymptomQualifierModal } from "./SymptomQualifierModal";
import { BODY_TAXONOMY, recommend, regionsIn, type BodyView, type DurationId } from "./taxonomy";

/**
 * Door 1: not sure who to see. Tap where it hurts, say what it is like, and
 * get the kind of doctor to book, or an emergency warning. The same flow
 * works from a plain list for anyone who cannot use the drawing.
 */
export function BodyGuide() {
  const [view, setView] = useState<BodyView>("front");
  const [region, setRegion] = useState<string | null>(null);
  const [symptoms, setSymptoms] = useState<string[]>([]);
  const [duration, setDuration] = useState<DurationId | null>(null);
  const [done, setDone] = useState(false);
  const [asList, setAsList] = useState(false);

  function choose(id: string) {
    setRegion(id);
    setSymptoms([]);
    setDuration(null);
    setDone(false);
  }

  function restart() {
    setRegion(null);
    setSymptoms([]);
    setDuration(null);
    setDone(false);
  }

  const chosen = region ? BODY_TAXONOMY[region] : null;

  return (
    <div className="bm-guide" data-stage={done ? "result" : chosen ? "qualify" : "map"}>
      {asList ? (
        <div className="bm-list">
          <p className="bm-list__title">Where is the discomfort?</p>
          <ul>
            {Object.values(BODY_TAXONOMY).map((r) => (
              <li key={r.id}>
                <button type="button" className="bm-chip" aria-pressed={region === r.id} onClick={() => choose(r.id)}>
                  {r.label}
                </button>
              </li>
            ))}
          </ul>
        </div>
      ) : (
        <BodyMapCanvas
          view={view}
          onViewChange={(v) => {
            setView(v);
            if (region && !BODY_TAXONOMY[region]?.views.includes(v)) restart();
          }}
          selected={region}
          onSelect={choose}
        />
      )}

      <button type="button" className="lm-link-button bm-switch" onClick={() => setAsList((l) => !l)}>
        {asList ? "Use the body map instead" : "Can't use the body map? Choose from a list"}
      </button>

      {chosen && !done && (
        <SymptomQualifierModal
          region={chosen}
          symptoms={symptoms}
          onToggle={(id) => setSymptoms((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]))}
          duration={duration}
          onDuration={setDuration}
          onDone={() => setDone(true)}
          onClose={restart}
        />
      )}
      {chosen && done && (
        <ClinicalRecommendationCard
          regionId={chosen.id}
          duration={duration}
          recommendation={recommend(chosen.id, symptoms)}
          onRestart={restart}
        />
      )}
      {!chosen && !asList && (
        <p className="bm-hint">
          {regionsIn(view).length} areas on the {view}. Turn to the {view === "front" ? "back" : "front"} for{" "}
          {view === "front" ? "the spine and kidneys" : "the chest and stomach"}.
        </p>
      )}
    </div>
  );
}
