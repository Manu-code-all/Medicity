import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { ApiError } from "../../api/client";
import { desk } from "../../api/endpoints";
import type { DeskVisit } from "../../api/types";
import { DESK_TODAY_KEY } from "../../lib/clinicKeys";
import { addDays, isoDay, shortDay } from "../../lib/dates";
import { formatTime } from "../../lib/format";
import { QUEUE_POLL_MS } from "../queue/TokenCard";

/**
 * The clinic's day: every doctor's booked visits, with arrival, missed and
 * cancel buttons, and the walk-in lines. Refreshed every few seconds so
 * what the doctors and patients do shows up without a reload.
 */
export function DeskTodayPage() {
  const queryClient = useQueryClient();
  const [date, setDate] = useState<string | undefined>(undefined);
  const today = useQuery({
    queryKey: [...DESK_TODAY_KEY, date ?? "today"],
    queryFn: () => desk.today(date),
    refetchInterval: QUEUE_POLL_MS,
  });
  const refresh = () => void queryClient.invalidateQueries({ queryKey: DESK_TODAY_KEY });

  const arrived = useMutation({ mutationFn: desk.arrived, onSuccess: refresh });
  const noShow = useMutation({ mutationFn: desk.noShow, onSuccess: refresh });
  const cancel = useMutation({ mutationFn: (id: string) => desk.cancel(id), onSuccess: refresh });
  const error = [arrived.error, noShow.error, cancel.error].find(Boolean);

  const data = today.data;
  const shown = data?.date ?? date;

  return (
    <div className="stack">
      <header className="section__head">
        <div>
          <h1 className="portal__title">Front desk</h1>
          <p className="muted">{shown ? shortDay(shown) : "Today"}</p>
        </div>
        <div className="rx-form__actions">
          <button
            type="button"
            className="button--quiet"
            disabled={!shown}
            onClick={() => shown && setDate(isoDay(addDays(new Date(`${shown}T00:00:00`), -1)))}
          >
            Previous day
          </button>
          <button type="button" className="button--quiet" onClick={() => setDate(undefined)}>
            Today
          </button>
          <button
            type="button"
            className="button--quiet"
            disabled={!shown}
            onClick={() => shown && setDate(isoDay(addDays(new Date(`${shown}T00:00:00`), 1)))}
          >
            Next day
          </button>
        </div>
      </header>

      {error && (
        <p className="error" role="alert">
          {error instanceof ApiError ? error.message : "Something went wrong."}
        </p>
      )}
      {today.isError && <p className="error">Could not load the day.</p>}
      {today.isPending && <div className="card skeleton" style={{ height: 220 }} />}

      {data && (
        <>
          <section className="card" aria-labelledby="desk-doctors">
            <h2 id="desk-doctors">Doctors</h2>
            {data.doctors.length === 0 && <p className="muted">No doctors are listed at this clinic yet.</p>}
            <ul className="desk__booked">
              {data.doctors.map((d) => (
                <li key={d.id}>
                  <strong>{d.fullName}</strong> <span className="muted small">{d.specialization}</span>{" "}
                  <span className="muted small">
                    · {d.walkInsOpen ? "taking walk-ins" : "walk-ins closed"} · {d.waiting} waiting
                    {!d.verified && " · waiting for verification"}
                  </span>
                </li>
              ))}
            </ul>
            <p>
              <Link to="/desk/walk-in">Add a walk-in</Link> · <Link to="/desk/book">Book a visit for a caller</Link>
            </p>
          </section>

          <section className="card" aria-labelledby="desk-visits">
            <h2 id="desk-visits">Booked visits</h2>
            {data.visits.length === 0 && <p className="muted">No booked visits on this day.</p>}
            <div className="desk">
              {data.visits.map((v) => (
                <VisitRow
                  key={v.id}
                  visit={v}
                  busy={arrived.isPending || noShow.isPending || cancel.isPending}
                  onArrived={() => arrived.mutate(v.id)}
                  onNoShow={() => noShow.mutate(v.id)}
                  onCancel={() => cancel.mutate(v.id)}
                />
              ))}
            </div>
          </section>

          <section className="card" aria-labelledby="desk-walkins">
            <h2 id="desk-walkins">Walk-ins</h2>
            {data.walkIns.length === 0 && <p className="muted">No walk-ins on this day.</p>}
            <ul className="desk__booked">
              {data.walkIns.map((t) => (
                <li key={t.id}>
                  <strong>#{t.tokenNo}</strong> {t.patientName}{" "}
                  <span className="muted small">
                    · {t.doctorName} · {t.status.toLowerCase()}
                  </span>
                </li>
              ))}
            </ul>
          </section>
        </>
      )}
    </div>
  );
}

function VisitRow({
  visit,
  busy,
  onArrived,
  onNoShow,
  onCancel,
}: {
  visit: DeskVisit;
  busy: boolean;
  onArrived: () => void;
  onNoShow: () => void;
  onCancel: () => void;
}) {
  const open = visit.status === "BOOKED";
  return (
    <article className="desk__token" aria-label={`${visit.patientName} at ${formatTime(visit.scheduledAt)}`}>
      <strong className="desk__no">{formatTime(visit.scheduledAt)}</strong>
      <div>
        <p>
          {visit.patientName} <span className="muted small">with {visit.doctorName}</span>
        </p>
        <p className="muted small">
          {visit.status === "BOOKED"
            ? visit.arrivedAt
              ? `Arrived ${formatTime(visit.arrivedAt)}`
              : "Expected"
            : visit.status.replace("_", " ").toLowerCase()}
          {visit.patientPhone && ` · ${visit.patientPhone}`}
          {visit.bookedByDesk && " · booked by the desk"}
          {visit.reason && ` · ${visit.reason}`}
        </p>
      </div>
      {open && (
        <div className="desk__actions">
          {!visit.arrivedAt && (
            <button
              type="button"
              className="button--sm"
              disabled={busy}
              onClick={onArrived}
              aria-label={`Mark ${visit.patientName} arrived`}
            >
              Arrived
            </button>
          )}
          {!visit.arrivedAt && (
            <button type="button" className="link" disabled={busy} onClick={onNoShow} aria-label={`Mark ${visit.patientName} as missed`}>
              Did not come
            </button>
          )}
          <button type="button" className="link" disabled={busy} onClick={onCancel} aria-label={`Cancel ${visit.patientName}'s visit`}>
            Cancel
          </button>
        </div>
      )}
    </article>
  );
}
