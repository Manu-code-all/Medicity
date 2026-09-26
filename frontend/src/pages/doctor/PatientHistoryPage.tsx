import { useQuery } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "react-router-dom";
import { ApiError } from "../../api/client";
import { workspace } from "../../api/endpoints";
import { formatDate } from "../../lib/format";
import { VisitCard } from "../portal/VisitCard";

export function PatientHistoryPage() {
  const { patientId = "" } = useParams();
  const navigate = useNavigate();
  const history = useQuery({
    queryKey: ["workspace", "history", patientId],
    queryFn: () => workspace.patientHistory(patientId),
  });

  if (history.isPending) return <div className="card skeleton" style={{ height: 240 }} />;
  if (history.isError) {
    return (
      <p className="error">
        {history.error instanceof ApiError && history.error.status === 403
          ? "You can only view the history of your own patients."
          : "Could not load this patient's history."}
      </p>
    );
  }

  const { patient, visits, prescriptions } = history.data;

  return (
    <div className="stack">
      <p>
        <button type="button" className="link" onClick={() => navigate(-1)}>
          ← Back
        </button>
      </p>
      <header>
        <h1 className="portal__title">{patient.fullName}</h1>
        <p className="muted">
          {patient.age} yrs · {patient.gender.toLowerCase()}
          {patient.bloodGroup && ` · blood group ${patient.bloodGroup}`} · {visits.length} visits
        </p>
      </header>

      <div className="two-col">
        <section>
          <h2 className="portal__subtitle">Visits</h2>
          <ul className="visit-list">
            {visits.map((v) => (
              <VisitCard key={v.id} visit={v} />
            ))}
          </ul>
        </section>
        <section>
          <h2 className="portal__subtitle">Current prescriptions</h2>
          {prescriptions.length === 0 && <p className="muted">None.</p>}
          {prescriptions.map((rx) => (
            <article key={rx.id} className="card rx-mini">
              <p className="eyebrow">
                {formatDate(rx.issuedAt)} · {rx.doctorName}
                {rx.revised && " · revised"}
              </p>
              <strong>{rx.diagnosis}</strong>
              <ul>
                {rx.items.map((item) => (
                  <li key={item.medicine}>
                    {item.medicine} {item.strength} — <span className="muted">{item.frequency}</span>
                  </li>
                ))}
              </ul>
            </article>
          ))}
          <p className="muted small">Each view of this page is recorded in the audit log.</p>
          <Link to="/doctor">Back to schedule</Link>
        </section>
      </div>
    </div>
  );
}
