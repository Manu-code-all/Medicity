import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { ArrowRight } from "@phosphor-icons/react";
import { storeQueue, storeReservations, storeWorkspace } from "../../api/endpoints";
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
  // The figures above the list share their cache with the pages they open.
  const pending = useQuery({ queryKey: ["store", "queue", "pending"], queryFn: () => storeQueue.list("pending") });
  const held = useQuery({ queryKey: ["store", "reservations", "held"], queryFn: () => storeReservations.list("held") });
  const insights = useQuery({ queryKey: ["store", "insights"], queryFn: storeWorkspace.insights });
  const week = insights.data?.summary;

  return (
    <div className="stack">
      <header>
        <h1 className="portal__title">Questions from patients</h1>
        <p className="muted">Each one is a prescription a doctor issued in Medicity. Answer per medicine.</p>
      </header>

      <div className="bento">
        <div className={pending.data?.length ? "tile tile--lead b-4" : "tile b-4"}>
          <span className="tile__label">Waiting for you</span>
          <strong className="tile__figure">{pending.data?.length ?? "–"}</strong>
          <span className="tile__hint">Patients see your answer the moment you send it</span>
        </div>
        <Link to="/store/reservations" className="tile tile--link b-4">
          <span className="tile__label">To keep aside</span>
          <strong className="tile__figure">{held.data?.length ?? "–"}</strong>
          <span className="tile__hint">Reserved medicines waiting for pick up</span>
          <ArrowRight className="tile__arrow" size={16} aria-hidden="true" />
        </Link>
        <Link to="/store/insights" className="tile tile--link b-4">
          <span className="tile__label">Minutes to answer</span>
          <strong className="tile__figure">{week?.medianMinutesToAnswer ?? "–"}</strong>
          <span className="tile__hint">
            {week ? `Median this week, ${week.answered} of ${week.questionsReceived} answered` : "Median this week"}
          </span>
          <ArrowRight className="tile__arrow" size={16} aria-hidden="true" />
        </Link>
      </div>

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

      <ul className={queue.data?.length ? "store-list panel panel--pad" : "store-list"}>
        {queue.data?.map((q) => (
          <li key={q.id}>
            <Link to={`/store/requests/${q.id}`} className="card request-row request-row--ticket">
              <div className="request-row__stub">
                <span className="request-row__distance">{formatDistance(q.distanceM)}</span>
                <span>away · {formatTime(q.createdAt)}</span>
              </div>
              <div>
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
