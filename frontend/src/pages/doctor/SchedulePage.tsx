import { useQuery } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router-dom";
import { workspace } from "../../api/endpoints";
import type { DoctorVisitSummary } from "../../api/types";
import { formatTime, greeting } from "../../lib/format";
import { useAuth } from "../../auth/context";

const STATUS_LABEL: Record<DoctorVisitSummary["status"], string> = {
  BOOKED: "Booked",
  COMPLETED: "Seen",
  CANCELLED: "Cancelled",
  NO_SHOW: "Missed",
};

/** yyyy-mm-dd in the viewer's own time zone. */
function toKey(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, "0");
  const d = String(date.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

function fromKey(key: string | null): Date {
  if (!key || !/^\d{4}-\d{2}-\d{2}$/.test(key)) return new Date();
  const [y = 0, m = 1, d = 1] = key.split("-").map(Number);
  return new Date(y, m - 1, d);
}

export function SchedulePage() {
  const { session } = useAuth();
  // The day lives in the URL so the back button returns to the same day.
  const [params, setParams] = useSearchParams();
  const day = fromKey(params.get("day"));
  const isToday = toKey(day) === toKey(new Date());

  // Midnight to midnight in the doctor's own time zone. The server takes
  // instants and never has to guess where the doctor is.
  const start = new Date(day.getFullYear(), day.getMonth(), day.getDate());
  const end = new Date(day.getFullYear(), day.getMonth(), day.getDate() + 1);

  const visits = useQuery({
    queryKey: ["workspace", "visits", toKey(day)],
    queryFn: () => workspace.visits(start.toISOString(), end.toISOString()),
  });

  function shift(days: number) {
    const next = new Date(day.getFullYear(), day.getMonth(), day.getDate() + days);
    setParams(toKey(next) === toKey(new Date()) ? {} : { day: toKey(next) });
  }

  const rows = visits.data ?? [];
  const waiting = rows.filter((v) => v.status === "BOOKED" && new Date(v.scheduledAt) <= new Date()).length;

  return (
    <div className="stack">
      <header>
        <h1 className="portal__title">
          {isToday ? `${greeting()}, ${session?.fullName ?? "Doctor"}` : "Schedule"}
        </h1>
        <p className="muted">
          {day.toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "long", year: "numeric" })}
          {visits.data && ` · ${rows.length} ${rows.length === 1 ? "visit" : "visits"}`}
          {waiting > 0 && ` · ${waiting} waiting to be closed`}
        </p>
      </header>

      <div className="day-nav">
        <button type="button" className="tab" onClick={() => shift(-1)} aria-label="Previous day">
          ← Previous
        </button>
        {!isToday && (
          <button type="button" className="tab" onClick={() => setParams({})}>
            Today
          </button>
        )}
        <button type="button" className="tab" onClick={() => shift(1)} aria-label="Next day">
          Next →
        </button>
      </div>

      {visits.isError && <p className="error">Could not load the schedule.</p>}
      {visits.isPending && <div className="card skeleton" style={{ height: 120 }} />}
      {visits.data && rows.length === 0 && (
        <div className="card empty">
          <h2>No visits</h2>
          <p className="muted">Nothing is booked for this day.</p>
        </div>
      )}

      <ul className="visit-list">
        {rows.map((v) => {
          const needsClosing = v.status === "BOOKED" && new Date(v.scheduledAt) <= new Date();
          return (
            <li key={v.id}>
              <Link to={`/doctor/visits/${v.id}`} className={`card sched sched--${v.status.toLowerCase()}`}>
                <div className="sched__time">
                  <strong>{formatTime(v.scheduledAt)}</strong>
                  <span className="muted">{formatTime(v.endsAt)}</span>
                </div>
                <div className="sched__body">
                  <div className="visit__head">
                    <strong>{v.patient.fullName}</strong>
                    <span className={`badge badge--${v.status.toLowerCase()}`}>{STATUS_LABEL[v.status]}</span>
                  </div>
                  <p className="muted visit__meta">
                    {v.patient.age} yrs · {v.patient.gender.toLowerCase()}
                    {v.patient.bloodGroup && ` · ${v.patient.bloodGroup}`}
                  </p>
                  {v.reason && <p className="visit__reason">{v.reason}</p>}
                  <p className="sched__flags">
                    {needsClosing && <span className="badge badge--no_show">Waiting to be closed</span>}
                    {v.prescriptionId && (
                      <span className="badge badge--completed">
                        {v.dispensedAt ? "Prescribed · dispensed" : "Prescribed"}
                      </span>
                    )}
                  </p>
                </div>
              </Link>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
