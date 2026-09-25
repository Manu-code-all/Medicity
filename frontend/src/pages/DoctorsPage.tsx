import { useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { doctors } from "../api/endpoints";

const SPECIALIZATIONS = [
  "Cardiology",
  "Neurology",
  "Orthopaedics",
  "Paediatrics",
  "Dermatology",
  "General Medicine",
];

export function DoctorsPage() {
  const [specialization, setSpecialization] = useState("");
  const [nameQuery, setNameQuery] = useState("");

  const query = useQuery({
    queryKey: ["doctors", specialization, nameQuery],
    queryFn: () => doctors.search(specialization || undefined, nameQuery || undefined),
    // Keeps the previous results on screen while a new filter loads, so the
    // list does not collapse to a spinner on every keystroke.
    placeholderData: keepPreviousData,
  });

  return (
    <section>
      <h1>Find a doctor</h1>

      <div className="filters">
        <label htmlFor="q" className="sr-only">
          Search by name
        </label>
        <input
          id="q"
          placeholder="Search by name"
          value={nameQuery}
          onChange={(e) => setNameQuery(e.target.value)}
        />

        <label htmlFor="spec" className="sr-only">
          Specialization
        </label>
        <select
          id="spec"
          value={specialization}
          onChange={(e) => setSpecialization(e.target.value)}
        >
          <option value="">All specializations</option>
          {SPECIALIZATIONS.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
      </div>

      {query.isPending && <p className="muted">Loading…</p>}
      {query.isError && <p className="error">Could not load doctors.</p>}

      {query.data && query.data.content.length === 0 && (
        <p className="muted">No doctors match that search.</p>
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
