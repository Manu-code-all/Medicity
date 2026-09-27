import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { storeQueue } from "../../api/endpoints";
import { formatTime, relativeFromNow } from "../../lib/format";
import { formatDistance } from "../../lib/geo";

/** Questions from patients nearby. Answer yes, partly or no; no stock list to keep. */
export function QueuePage() {
  const [show, setShow] = useState<"pending" | "answered">("pending");
  const queue = useQuery({
    queryKey: ["store", "queue", show],
    queryFn: () => storeQueue.list(show),
    refetchInterval: show === "pending" ? 30_000 : false,
  });

  return (
    <div className="stack">
      <header>
        <h1 className="portal__title">Questions from patients</h1>
        <p className="muted">Each one is a prescription a doctor issued in Medicity. Answer per medicine.</p>
      </header>

      <div className="tabs" role="tablist">
        {(["pending", "answered"] as const).map((tab) => (
          <button
            key={tab}
            type="button"
            role="tab"
            aria-selected={show === tab}
            className={show === tab ? "tab is-active" : "tab"}
            onClick={() => setShow(tab)}
          >
            {tab === "pending" ? "Waiting for you" : "Answered"}
          </button>
        ))}
      </div>

      {queue.isError && <p className="error">Could not load questions.</p>}
      {queue.isPending && <div className="card skeleton" style={{ height: 120 }} />}
      {queue.data?.length === 0 && (
        <div className="card empty">
          <h2>{show === "pending" ? "Nothing waiting" : "Nothing answered yet"}</h2>
          <p className="muted">
            {show === "pending"
              ? "New questions from patients near your store appear here, and as a notification."
              : "Questions you answer move here."}
          </p>
        </div>
      )}

      <ul className="store-list">
        {queue.data?.map((q) => (
          <li key={q.id}>
            <Link to={`/store/requests/${q.id}`} className="card request-row">
              <div>
                <p className="eyebrow">
                  {formatTime(q.createdAt)} · {formatDistance(q.distanceM)} away
                </p>
                <strong>
                  {q.patientName} · {q.medicines} {q.medicines === 1 ? "medicine" : "medicines"}
                </strong>
                <p className="muted">Prescribed by {q.doctorName}</p>
              </div>
              <div className="request-row__side">
                {q.myStatus === "PENDING" ? (
                  <span className="muted">closes {relativeFromNow(q.expiresAt)}</span>
                ) : (
                  <span className="badge badge--completed">Answered</span>
                )}
              </div>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
