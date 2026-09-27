import { useEffect, useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router-dom";
import { doctors } from "../api/endpoints";
import { BODY_TAXONOMY, specialistPhrase } from "../components/bodymap/taxonomy";

/**
 * The directory. Its filters live in the URL (?specialty=, ?q=, and ?zone=
 * when the body guide sent the visitor here), so the landing page's two doors
 * link straight into a filtered list, and a filtered list can be shared.
 */
export function DoctorsPage() {
  const [params, setParams] = useSearchParams();
  const specialization = params.get("specialty") ?? "";
  const zone = params.get("zone");
  const [nameQuery, setNameQuery] = useState(params.get("q") ?? "");

  // Typing updates the URL after a pause, not on every keystroke.
  useEffect(() => {
    const t = window.setTimeout(() => {
      setParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          if (nameQuery.trim()) next.set("q", nameQuery.trim());
          else next.delete("q");
          return next;
        },
        { replace: true },
      );
    }, 300);
    return () => window.clearTimeout(t);
  }, [nameQuery, setParams]);

  const q = params.get("q") ?? "";
  const query = useQuery({
    queryKey: ["doctors", specialization, q],
    queryFn: () => doctors.search(specialization || undefined, q || undefined),
    // Keeps the previous results on screen while a new filter loads, so the
    // list does not collapse to a spinner on every keystroke.
    placeholderData: keepPreviousData,
  });
  const specialties = useQuery({ queryKey: ["doctors", "specialties"], queryFn: doctors.specialties, staleTime: 5 * 60_000 });

  function setSpecialization(value: string) {
    setParams((prev) => {
      const next = new URLSearchParams(prev);
      if (value) next.set("specialty", value);
      else next.delete("specialty");
      next.delete("zone");
      return next;
    });
  }

  const area = zone ? BODY_TAXONOMY[zone] : undefined;
  const fallback = area && area.routing.primarySpecialty !== specialization ? area.routing.primarySpecialty : area?.routing.secondarySpecialty;

  return (
    <section>
      <h1>Find a doctor</h1>

      {area && specialization && (
        <p className="notice" role="status">
          From the body guide: <strong>{area.label}</strong>. Showing {specialistPhrase(specialization).replace(/^an? /, "")}s.{" "}
          <Link to="/#main">Change the answer</Link>
        </p>
      )}

      <div className="filters">
        <label htmlFor="q" className="sr-only">
          Search by name or speciality
        </label>
        <input
          id="q"
          placeholder="Search by name or speciality"
          value={nameQuery}
          onChange={(e) => setNameQuery(e.target.value)}
        />

        <label htmlFor="spec" className="sr-only">
          Specialization
        </label>
        <select id="spec" value={specialization} onChange={(e) => setSpecialization(e.target.value)}>
          <option value="">All specializations</option>
          {specialties.data?.map((s) => (
            <option key={s.name} value={s.name}>
              {s.name} ({s.doctors})
            </option>
          ))}
          {specialization && !specialties.data?.some((s) => s.name === specialization) && (
            <option value={specialization}>{specialization}</option>
          )}
        </select>
      </div>

      {query.isPending && <p className="muted">Loading…</p>}
      {query.isError && <p className="error">Could not load doctors.</p>}

      {query.data && query.data.content.length === 0 && (
        <div className="card empty">
          <p>No doctors match that search.</p>
          {area && fallback && fallback !== specialization && (
            <p>
              <button type="button" className="link" onClick={() => setSpecialization(fallback)}>
                See {specialistPhrase(fallback)} instead
              </button>
            </p>
          )}
        </div>
      )}

      <ul className="doctor-list">
        {query.data?.content.map((doctor) => (
          <li key={doctor.id} className="card doctor">
            <div>
              <h2>{doctor.fullName}</h2>
              <p className="muted">
                {doctor.specialization} · {doctor.yearsExperience} yrs
              </p>
              {doctor.bio && <p>{doctor.bio}</p>}
            </div>
            <div className="doctor__action">
              <p className="fee">₹{doctor.consultationFee}</p>
              <Link className="button" to={`/doctors/${doctor.id}/book`}>
                Book
              </Link>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
