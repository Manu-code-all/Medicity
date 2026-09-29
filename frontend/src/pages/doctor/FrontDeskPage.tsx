import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { ApiError } from "../../api/client";
import { queue, workspace } from "../../api/endpoints";
import type { QueueToken } from "../../api/types";
import { formatTime } from "../../lib/format";
import { QUEUE_POLL_MS } from "../queue/TokenCard";

const DESK_KEY = ["queue", "desk"];

/**
 * Today at the clinic: booked arrivals and the walk-in line side by side,
 * and one button to call the next walk-in. Refreshed every few seconds, so
 * tokens taken on patients' phones appear on their own.
 */
export function FrontDeskPage() {
  const queryClient = useQueryClient();
  const desk = useQuery({ queryKey: DESK_KEY, queryFn: queue.desk, refetchInterval: QUEUE_POLL_MS });
  const booked = useQuery({ queryKey: ["doctor", "today"], queryFn: () => workspace.visits(...todayRange()) });
  const refresh = () => void queryClient.invalidateQueries({ queryKey: DESK_KEY });

  const next = useMutation({ mutationFn: queue.callNext, onSuccess: refresh });
  const finish = useMutation({
    mutationFn: ({ id, seen }: { id: string; seen: boolean }) => queue.finish(id, seen),
    onSuccess: refresh,
  });
  const toggle = useMutation({ mutationFn: (open: boolean) => queue.setOpen(open), onSuccess: refresh });
  const error = [next.error, finish.error, toggle.error].find(Boolean);

  const tokens = desk.data?.tokens ?? [];
  const called = tokens.filter((t) => t.status === "CALLED");
  const waiting = tokens.filter((t) => t.status === "WAITING");
  const done = tokens.filter((t) => t.status !== "CALLED" && t.status !== "WAITING");

  return (
    <div className="stack">
      <header className="section__head">
        <div>
          <h1 className="portal__title">Front desk</h1>
          {desk.data && (
            <p className="muted">
              {desk.data.status.open ? "Taking walk-ins" : "Closed to new walk-ins"} · {waiting.length} waiting
            </p>
          )}
        </div>
        {desk.data && (
          <button type="button" className="link" onClick={() => toggle.mutate(!desk.data.status.open)}>
            {desk.data.status.open ? "Close today's queue" : "Open today's queue"}
          </button>
        )}
      </header>

      {error && (
        <p className="error" role="alert">
          {error instanceof ApiError ? error.message : "Something went wrong."}
        </p>
      )}

      <div className="desk">
        <section className="card" aria-labelledby="walk-ins">
          <div className="section__head">
            <h2 id="walk-ins">Walk-ins</h2>
            <button type="button" disabled={waiting.length === 0 || next.isPending} onClick={() => next.mutate()}>
              {waiting.length ? `Call #${waiting[0]!.tokenNo}` : "Nobody waiting"}
            </button>
          </div>
          {called.map((t) => (
            <TokenRow key={t.id} token={t}>
              <button type="button" className="button--sm" onClick={() => finish.mutate({ id: t.id, seen: true })}>
                Seen
              </button>
              <button type="button" className="link" onClick={() => finish.mutate({ id: t.id, seen: false })}>
                Did not come in
              </button>
            </TokenRow>
          ))}
          {waiting.map((t) => (
            <TokenRow key={t.id} token={t} />
          ))}
          {tokens.length === 0 && <p className="muted">No walk-ins yet today.</p>}
          {done.length > 0 && (
            <details className="desk__done">
              <summary>{done.length} closed</summary>
              {done.map((t) => (
                <TokenRow key={t.id} token={t} />
              ))}
            </details>
          )}
        </section>

        <section className="card" aria-labelledby="booked">
          <h2 id="booked">Booked today</h2>
          {booked.data?.length === 0 && <p className="muted">No appointments today.</p>}
          <ul className="desk__booked">
            {booked.data?.map((v) => (
              <li key={v.id}>
                <span className="desk__time">{formatTime(v.scheduledAt)}</span>{" "}
                <Link to={`/doctor/visits/${v.id}`}>{v.patient.fullName}</Link>{" "}
                <span className="muted small">{v.status === "BOOKED" ? "" : v.status.toLowerCase()}</span>
              </li>
            ))}
          </ul>
        </section>
      </div>
    </div>
  );
}

const LABEL: Record<QueueToken["status"], string> = {
  WAITING: "Waiting",
  CALLED: "Called in",
  SEEN: "Seen",
  MISSED: "Did not come in",
  LEFT: "Left the queue",
};

function TokenRow({ token, children }: { token: QueueToken; children?: React.ReactNode }) {
  return (
    <div className={`desk__token desk__token--${token.status.toLowerCase()}`}>
      <strong className="desk__no">#{token.tokenNo}</strong>
      <div>
        <p>
          {token.patientName} <span className="muted small">· {LABEL[token.status]}</span>
        </p>
        {token.reason && <p className="muted small">{token.reason}</p>}
      </div>
      {children && <div className="desk__actions">{children}</div>}
    </div>
  );
}

function todayRange(): [string, string] {
  const from = new Date();
  from.setHours(0, 0, 0, 0);
  const to = new Date(from);
  to.setDate(to.getDate() + 1);
  return [from.toISOString(), to.toISOString()];
}
