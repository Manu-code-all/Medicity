import { useState } from "react";
import type { Prescription } from "../../api/types";
import { disclaimer, instruction, LANGUAGES, shareText, type Language } from "../../lib/instructions";

const LANGUAGE_KEY = "medicity.language";

function savedLanguage(): Language {
  try {
    const saved = localStorage.getItem(LANGUAGE_KEY);
    return LANGUAGES.some((l) => l.code === saved) ? (saved as Language) : "en";
  } catch {
    return "en";
  }
}

/**
 * How to take each medicine, in the language the patient (or whoever looks
 * after them) reads best, with the doctor's own words underneath. Shareable on
 * WhatsApp for a parent who manages their medicines on their phone.
 */
export function HowToTake({ prescription }: { prescription: Prescription }) {
  const [language, setLanguage] = useState<Language>(savedLanguage);

  function choose(next: Language) {
    setLanguage(next);
    try {
      localStorage.setItem(LANGUAGE_KEY, next);
    } catch {
      // Remembering the choice is a convenience only.
    }
  }

  const lines = prescription.items.map((item) => ({
    item,
    how: instruction(item.frequency, item.durationDays, language),
  }));

  return (
    <section className="how" aria-label="How to take your medicines">
      <div className="how__head">
        <h3>How to take</h3>
        <label className="inline-field">
          <span className="sr-only">Language</span>
          <select value={language} onChange={(e) => choose(e.target.value as Language)}>
            {LANGUAGES.map((l) => (
              <option key={l.code} value={l.code}>
                {l.name}
              </option>
            ))}
          </select>
        </label>
      </div>
      <ol className="how__list" lang={language}>
        {lines.map(({ item, how }) => (
          <li key={item.medicineId}>
            <strong lang="en">
              {item.medicine} {item.strength}
            </strong>{" "}
            <span className="muted" lang="en">
              {item.dosage}
            </span>
            <p className={how.understood ? "how__parts" : "how__parts how__parts--unclear"}>
              {how.parts.join(" · ")}
            </p>
            {(language !== "en" || !how.understood) && (
              <p className="muted small" lang="en">
                Doctor wrote: “{item.frequency}”, {item.durationDays} days
              </p>
            )}
          </li>
        ))}
      </ol>
      <p className="muted small">{disclaimer(language)}</p>
      <a
        className="button button--ghost whatsapp"
        href={`https://wa.me/?text=${encodeURIComponent(shareText(prescription, language))}`}
        target="_blank"
        rel="noreferrer"
      >
        Share on WhatsApp
      </a>
    </section>
  );
}
