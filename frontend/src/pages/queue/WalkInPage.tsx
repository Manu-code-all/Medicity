import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";
import { ApiError } from "../../api/client";
import { queue } from "../../api/endpoints";
import { ActingBanner } from "../../components/ActingBanner";
import { QUEUE_POLL_MS, TokenCard } from "./TokenCard";

/**
 * Door 2: see a doctor today without an appointment. Shows the line as it
 * is, takes a token, and then follows it: places move up as the desk calls
 * people in.
 */
export function WalkInPage() {
  const { doctorId = "" } = useParams();
  const queryClient = useQueryClient();
  const [reason, setReason] = useState("");

  const status = useQuery({
    queryKey: ["queue", "status", doctorId],
    queryFn: () => queue.status(doctorId),
    refetchInterval: QUEUE_POLL_MS,
  });
  const mine = useQuery({ queryKey: ["queue", "mine"], queryFn: queue.mine, refetchInterval: QUEUE_POLL_MS });
  const join = useMutation({
    mutationFn: () => queue.join(doctorId, reason),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ["queue"] }),
  });

  const held = mine.data?.filter((t) => t.doctorId === doctorId) ?? [];

  return (
    <section className="walk-in">
      <h1>Walk in today</h1>
      <ActingBanner verb="Taking a token" />

      {status.isPending && <p className="muted">Looking at the line…</p>}
      {status.isError && <p className="error">Could not load this doctor's queue.</p>}
      {status.data && (
        <p className="walk-in__line">
          {status.data.nowServing ? <>Now seeing <strong>#{status.data.nowServing}</strong> · </> : null}
          <strong>{status.data.waiting}</strong> waiting · about {status.data.estimatedWaitMinutes} min for someone
          joining now
        </p>
      )}

      {held.map((t) => (
        <TokenCard key={t.id} token={t} />
      ))}

      {status.data && !status.data.open && <p className="notice">{status.data.closedReason}</p>}

      {status.data?.open && (
        <form
          className="walk-in__form"
          onSubmit={(e) => {
            e.preventDefault();
            join.mutate();
          }}
        >
          <label htmlFor="walk-in-reason">What brings you in? (optional)</label>
          <input id="walk-in-reason" maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)} />
          {join.isError && (
            <p className="error" role="alert">
              {join.error instanceof ApiError ? join.error.message : "Could not take a token."}
            </p>
          )}
          <button type="submit" disabled={join.isPending}>
            {join.isPending ? "Taking a token…" : held.length ? "Take another token (family member)" : "Take a token"}
          </button>
          <p className="muted small">
            Come to the clinic when you are two or three away; the page updates by itself. Prefer a fixed time?{" "}
            <Link to={`/doctors/${doctorId}/book`}>Book an appointment</Link>.
          </p>
        </form>
      )}
    </section>
  );
}
