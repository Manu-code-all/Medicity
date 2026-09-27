import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { medicineRequests } from "../../api/endpoints";
import { formatDate, formatTime } from "../../lib/format";
import { requestStatusLabel } from "../../lib/requests";

/** The patient's questions to chemists, newest first. */
export function RequestsPage() {
  const list = useQuery({ queryKey: ["medicine-requests"], queryFn: medicineRequests.mine });

  return (
    <div className="stack">
      <header>
        <h1 className="portal__title">Chemist answers</h1>
        <p className="muted">
          Questions you sent to chemists nearby. Start one from a prescription with “Ask chemists nearby”.
        </p>
      </header>

      {list.isError && <p className="error">Could not load your questions.</p>}
      {list.isPending && <div className="card skeleton" style={{ height: 120 }} />}
      {list.data?.length === 0 && (
        <div className="card empty">
          <h2>No questions yet</h2>
          <p className="muted">Ask every chemist around you at once, from any prescription.</p>
          <Link className="button" to="/portal/prescriptions">
            Go to prescriptions
          </Link>
        </div>
      )}

      <ul className="store-list">
        {list.data?.map((r) => (
          <li key={r.id}>
            <Link to={`/portal/requests/${r.id}`} className="card request-row">
              <div>
                <p className="eyebrow">
                  {formatDate(r.createdAt)} · {formatTime(r.createdAt)}
                </p>
                <strong>{r.diagnosis}</strong>
                <p className="muted">
                  {r.medicines} {r.medicines === 1 ? "medicine" : "medicines"} · {r.doctorName}
                </p>
              </div>
              <div className="request-row__side">
                <span className={`badge badge--${r.status.toLowerCase()}`}>{requestStatusLabel(r.status)}</span>
                <span>
                  {r.answers} of {r.storesAsked} answered
                </span>
              </div>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
