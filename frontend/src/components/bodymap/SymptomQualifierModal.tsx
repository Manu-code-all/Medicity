import { DURATIONS, type BodyRegion, type DurationId } from "./taxonomy";

/**
 * What the discomfort is like, for the area just chosen. A sheet over the
 * lower part of the guide rather than a modal: the body stays visible, and
 * choosing another area simply replaces it.
 */
export function SymptomQualifierModal({
  region,
  symptoms,
  onToggle,
  duration,
  onDuration,
  onDone,
  onClose,
}: {
  region: BodyRegion;
  symptoms: string[];
  onToggle: (symptomId: string) => void;
  duration: DurationId | null;
  onDuration: (duration: DurationId) => void;
  onDone: () => void;
  onClose: () => void;
}) {
  return (
    <section className="bm-sheet" aria-labelledby="bm-sheet-title">
      <div className="bm-sheet__head">
        <h3 id="bm-sheet-title">{region.label}</h3>
        <button type="button" className="lm-link-button" onClick={onClose}>
          Change area
        </button>
      </div>

      <fieldset className="bm-choices">
        <legend>What is it like? Choose any that fit.</legend>
        {region.subSymptoms.map((s) => (
          <button
            key={s.id}
            type="button"
            className="bm-chip"
            data-flag={s.isRedFlag || undefined}
            aria-pressed={symptoms.includes(s.id)}
            onClick={() => onToggle(s.id)}
          >
            {s.label}
          </button>
        ))}
      </fieldset>

      <fieldset className="bm-choices">
        <legend>Since when?</legend>
        {DURATIONS.map((d) => (
          <button
            key={d.id}
            type="button"
            className="bm-chip"
            aria-pressed={duration === d.id}
            onClick={() => onDuration(d.id)}
          >
            {d.label}
          </button>
        ))}
      </fieldset>

      <button type="button" className="lm-button lm-button--block" disabled={symptoms.length === 0} onClick={onDone}>
        See who to consult
      </button>
    </section>
  );
}
