import { useEffect, useId, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { MagnifyingGlass } from "@phosphor-icons/react";
import { doctors } from "../../api/endpoints";
import { specialistPhrase, wordMatches } from "../bodymap/taxonomy";

const DEBOUNCE_MS = 300;

interface Option {
  key: string;
  label: string;
  detail: string;
  to: string;
}

/**
 * Door 2: search by doctor or speciality. Suggestions arrive 300 ms after
 * typing stops and are cached, so going back over the same letters is
 * instant. A combobox: arrow keys move through the list, Enter opens the
 * highlighted suggestion or searches for exactly what was typed, Escape
 * closes the list.
 */
export function DoctorOmnibar() {
  const navigate = useNavigate();
  const listId = useId();
  const [text, setText] = useState("");
  const [term, setTerm] = useState("");
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);

  useEffect(() => {
    const t = window.setTimeout(() => setTerm(text.trim()), DEBOUNCE_MS);
    return () => window.clearTimeout(t);
  }, [text]);

  const suggestions = useQuery({
    queryKey: ["doctors", "suggest", term.toLowerCase()],
    queryFn: () => doctors.suggest(term),
    enabled: term.length >= 2,
    staleTime: 5 * 60_000,
  });

  // Worked out here, instantly, from the body guide's words: no request needed.
  const words = wordMatches(term).map((w) => ({
    key: `w:${w.specialty}`,
    label: w.label,
    detail: `See ${specialistPhrase(w.specialty)}`,
    to: `/doctors?${new URLSearchParams(w.zone ? { specialty: w.specialty, zone: w.zone } : { specialty: w.specialty })}`,
  }));
  const options: Option[] = [
    ...words,
    ...(suggestions.data?.specialties ?? []).map((s) => ({
      key: `s:${s.name}`,
      label: s.name,
      detail: `${s.doctors} ${s.doctors === 1 ? "doctor" : "doctors"}`,
      to: `/doctors?${new URLSearchParams({ specialty: s.name })}`,
    })),
    ...(suggestions.data?.doctors ?? []).map((d) => ({
      key: `d:${d.id}`,
      label: d.fullName,
      detail: `${d.specialization} · ${d.yearsExperience} yrs`,
      to: `/doctors?${new URLSearchParams({ q: d.fullName })}`,
    })),
  ];
  const specialtyStart = words.length;
  const doctorStart = specialtyStart + (suggestions.data?.specialties.length ?? 0);
  const groupAt = (i: number) =>
    i === 0 && words.length > 0
      ? "For what you described"
      : i === specialtyStart && i < doctorStart
        ? "Specialities"
        : i === doctorStart
          ? "Doctors"
          : undefined;
  const showList = open && term.length >= 2 && (suggestions.isSuccess || words.length > 0);

  function go(to: string) {
    setOpen(false);
    navigate(to);
  }

  function submit() {
    const chosen = active >= 0 ? options[active] : undefined;
    if (chosen) go(chosen.to);
    else if (text.trim()) go(`/doctors?${new URLSearchParams({ q: text.trim() })}`);
  }

  return (
    <form
      className="omni"
      role="search"
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
    >
      <label htmlFor={`${listId}-input`} className="omni__label">
        Doctor or speciality
      </label>
      <div className="omni__field">
        <MagnifyingGlass size={20} weight="bold" aria-hidden="true" />
        <input
          id={`${listId}-input`}
          role="combobox"
          aria-expanded={showList}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={showList && active >= 0 ? `${listId}-${active}` : undefined}
          autoComplete="off"
          placeholder="Try 'knee', 'Dr. Menon' or 'skin'"
          value={text}
          onChange={(e) => {
            setText(e.target.value);
            setOpen(true);
            setActive(-1);
          }}
          onFocus={() => setOpen(true)}
          onBlur={() => window.setTimeout(() => setOpen(false), 120)}
          onKeyDown={(e) => {
            if (e.key === "ArrowDown" && options.length > 0) {
              e.preventDefault();
              setOpen(true);
              setActive((i) => (i + 1) % options.length);
            } else if (e.key === "ArrowUp" && options.length > 0) {
              e.preventDefault();
              setActive((i) => (i <= 0 ? options.length - 1 : i - 1));
            } else if (e.key === "Escape") {
              setOpen(false);
              setActive(-1);
            }
          }}
        />
        <button type="submit" className="lm-button lm-button--sm">
          Search
        </button>
      </div>

      {showList && (
        <ul id={listId} role="listbox" className="omni__list" aria-label="Suggestions">
          {options.length === 0 && (
            <li className="omni__empty" role="presentation">
              Nothing matches “{term}”. Press Search to look through every doctor.
            </li>
          )}
          {options.map((o, i) => (
            <li
              key={o.key}
              id={`${listId}-${i}`}
              role="option"
              aria-selected={i === active}
              className="omni__option"
              data-group-start={groupAt(i)}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => go(o.to)}
            >
              <span className="omni__option-label">{o.label}</span>
              <span className="omni__option-detail">{o.detail}</span>
            </li>
          ))}
        </ul>
      )}
    </form>
  );
}
