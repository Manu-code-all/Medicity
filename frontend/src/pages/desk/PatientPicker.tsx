import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { desk } from "../../api/endpoints";
import type { DeskPatientHit, DeskPatientRef } from "../../api/types";

/**
 * Who a desk action is for: a caller the clinic has met (found by name or
 * phone), a Medicity account (found by its whole email address), or someone
 * new, who is recorded for this clinic only. Reports `null` until the choice is
 * complete.
 */
export function PatientPicker({ onChange }: { onChange: (ref: DeskPatientRef | null) => void }) {
  const [mode, setMode] = useState<"find" | "new">("find");
  const [query, setQuery] = useState("");
  const [submitted, setSubmitted] = useState("");
  const [chosen, setChosen] = useState<DeskPatientHit | null>(null);
  const [name, setName] = useState("");
  const [phone, setPhone] = useState("");
  const [age, setAge] = useState("");

  const hits = useQuery({
    queryKey: ["desk", "patients", submitted],
    queryFn: () => desk.findPatients(submitted),
    enabled: mode === "find" && submitted.length >= 2,
  });

  useEffect(() => {
    if (mode === "find") {
      if (!chosen) return onChange(null);
      return onChange(chosen.patientId ? { patientId: chosen.patientId } : chosen.email ? { email: chosen.email } : null);
    }
    const years = Number(age);
    if (!name.trim() || age === "" || !Number.isFinite(years) || years < 0 || years > 120) return onChange(null);
    onChange({ newPatient: { fullName: name.trim(), phone: phone.trim() || undefined, ageYears: years } });
  }, [mode, chosen, name, phone, age, onChange]);

  return (
    <fieldset className="rx-form">
      <legend>Who is it for?</legend>
      <div className="rx-form__actions">
        <label>
          <input type="radio" name="who" checked={mode === "find"} onChange={() => setMode("find")} /> Someone we know
        </label>
        <label>
          <input type="radio" name="who" checked={mode === "new"} onChange={() => setMode("new")} /> A new caller
        </label>
      </div>

      {mode === "find" ? (
        <div className="stack">
          <div className="rx-form__actions">
            <label htmlFor="who-q" className="sr-only">
              Name, phone or full email
            </label>
            <input
              id="who-q"
              placeholder="Name, phone or full email"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") {
                  e.preventDefault();
                  setSubmitted(query.trim());
                  setChosen(null);
                }
              }}
            />
            <button
              type="button"
              className="button--quiet"
              onClick={() => {
                setSubmitted(query.trim());
                setChosen(null);
              }}
            >
              Search
            </button>
          </div>
          {hits.isError && <p className="error">Could not search.</p>}
          {hits.data?.length === 0 && <p className="muted">Nobody found. Try the whole email, or add a new caller.</p>}
          {hits.data && hits.data.length > 0 && (
            <ul className="desk__booked">
              {hits.data.map((h) => {
                const key = h.patientId ?? h.email ?? h.fullName;
                const on = chosen === h;
                return (
                  <li key={key}>
                    <button
                      type="button"
                      className={on ? "" : "button--quiet"}
                      aria-pressed={on}
                      onClick={() => setChosen(h)}
                    >
                      {h.fullName}
                    </button>{" "}
                    <span className="muted small">
                      {h.source === "ACCOUNT" ? `Medicity account · ${h.email}` : h.phone ?? "no phone recorded"}
                    </span>
                  </li>
                );
              })}
            </ul>
          )}
          {chosen && (
            <p role="status" className="muted">
              Selected: <strong>{chosen.fullName}</strong>
            </p>
          )}
        </div>
      ) : (
        <div className="stack">
          <label htmlFor="new-name">Full name</label>
          <input id="new-name" maxLength={120} value={name} onChange={(e) => setName(e.target.value)} />
          <div className="field-row">
            <div>
              <label htmlFor="new-phone">Phone</label>
              <input id="new-phone" inputMode="tel" value={phone} onChange={(e) => setPhone(e.target.value)} />
            </div>
            <div>
              <label htmlFor="new-age">Age (years)</label>
              <input
                id="new-age"
                type="number"
                min={0}
                max={120}
                value={age}
                onChange={(e) => setAge(e.target.value)}
              />
            </div>
          </div>
          <p className="muted small">
            Recorded for this clinic only. Without an account they get no reminders; give them the number to ring.
          </p>
        </div>
      )}
    </fieldset>
  );
}
