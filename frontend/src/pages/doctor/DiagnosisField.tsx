import { useEffect, useId, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { diagnoses } from "../../api/endpoints";

interface Props {
  diagnosis: string;
  code: string | null;
  onChange: (diagnosis: string, code: string | null) => void;
  error?: string | undefined;
}

/**
 * The diagnosis in the doctor's words, with an optional ICD-10 code found as
 * they type ("gerd", "bp", "K21"). Choosing a suggestion fills the words with
 * its title and keeps the code; the words can still be edited afterwards, and
 * the code removed. A combobox in the ARIA sense: arrows move, Enter chooses,
 * Escape closes.
 */
export function DiagnosisField({ diagnosis, code, onChange, error }: Props) {
  const listId = useId();
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const [term, setTerm] = useState("");

  // Asks once typing pauses, not on every keystroke.
  useEffect(() => {
    const t = window.setTimeout(() => setTerm(diagnosis.trim()), 250);
    return () => window.clearTimeout(t);
  }, [diagnosis]);

  const lookup = useQuery({
    queryKey: ["diagnoses", term],
    queryFn: () => diagnoses.search(term),
    enabled: open && term.length >= 2,
    staleTime: 60 * 60_000,
  });
  const options = open && term.length >= 2 ? (lookup.data ?? []) : [];

  function choose(i: number) {
    const picked = options[i];
    if (!picked) return;
    onChange(picked.title, picked.code);
    setOpen(false);
    setActive(-1);
  }

  return (
    <div className="dx">
      <label htmlFor="rx-diagnosis">Diagnosis</label>
      <input
        id="rx-diagnosis"
        role="combobox"
        aria-expanded={options.length > 0}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={active >= 0 ? `${listId}-${active}` : undefined}
        autoComplete="off"
        required
        maxLength={500}
        value={diagnosis}
        onChange={(e) => {
          onChange(e.target.value, code);
          setOpen(true);
          setActive(-1);
        }}
        onBlur={() => setOpen(false)}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown" && options.length) {
            e.preventDefault();
            setActive((a) => (a + 1) % options.length);
          } else if (e.key === "ArrowUp" && options.length) {
            e.preventDefault();
            setActive((a) => (a <= 0 ? options.length - 1 : a - 1));
          } else if (e.key === "Enter" && active >= 0) {
            e.preventDefault();
            choose(active);
          } else if (e.key === "Escape") {
            setOpen(false);
          }
        }}
      />
      {options.length > 0 && (
        <ul id={listId} role="listbox" className="dx__list" aria-label="ICD-10 codes">
          {options.map((o, i) => (
            <li
              key={o.code}
              id={`${listId}-${i}`}
              role="option"
              aria-selected={i === active}
              className="dx__option"
              // mousedown, not click: it runs before the input's blur closes the list.
              onMouseDown={(e) => {
                e.preventDefault();
                choose(i);
              }}
            >
              <span className="dx__code">{o.code}</span> {o.title}
            </li>
          ))}
        </ul>
      )}
      {code ? (
        <p className="dx__chosen">
          ICD-10 <strong>{code}</strong>{" "}
          <button type="button" className="link" onClick={() => onChange(diagnosis, null)}>
            Remove code
          </button>
        </p>
      ) : (
        <small className="muted">Type a condition, a code or an everyday word to add an ICD-10 code (optional).</small>
      )}
      {error && <span className="error field-error">{error}</span>}
    </div>
  );
}
