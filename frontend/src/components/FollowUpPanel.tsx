import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../api/client";
import { followUps } from "../api/endpoints";
import { formatDate, formatTime } from "../lib/format";

/**
 * A visit's follow-up thread. The patient asks (up to three questions while
 * the free period lasts); the doctor answers whatever is waiting. The server
 * decides what each side may send; this only mirrors it.
 */
export function FollowUpPanel({ appointmentId, side }: { appointmentId: string; side: "PATIENT" | "DOCTOR" }) {
  const queryClient = useQueryClient();
  const key = ["followups", appointmentId];
  const [text, setText] = useState("");
  const thread = useQuery({ queryKey: key, queryFn: () => followUps.thread(appointmentId) });
  const send = useMutation({
    mutationFn: () => followUps.post(appointmentId, text),
    onSuccess: (next) => {
      queryClient.setQueryData(key, next);
      setText("");
    },
  });

  if (thread.isPending) return <p className="muted small">Loading follow-up…</p>;
  if (thread.isError) return <p className="error">Could not load the follow-up questions.</p>;
  const t = thread.data;
  if (side === "DOCTOR" && t.messages.length === 0) return null;

  const canSend = side === "PATIENT" ? t.open && t.questionsLeft > 0 : t.awaitingDoctor;
  const closes = `${formatDate(t.closesAt)}, ${formatTime(t.closesAt)}`;

  return (
    <section className="followup" aria-label="Follow-up questions">
      {side === "PATIENT" && (
        <p className="muted small">
          {t.open
            ? `${t.questionsLeft} of 3 free questions left, until ${closes}.`
            : "The free follow-up period for this visit has ended."}
        </p>
      )}
      {t.messages.length > 0 && (
        <ol className="followup__thread">
          {t.messages.map((m) => (
            <li key={m.id} className={`followup__msg followup__msg--${m.sender.toLowerCase()}`}>
              <span className="followup__who">{m.sender === "PATIENT" ? "Patient" : "Doctor"}</span>
              <p>{m.body}</p>
              <span className="muted small">
                {formatDate(m.sentAt)}, {formatTime(m.sentAt)}
              </span>
            </li>
          ))}
        </ol>
      )}
      {side === "DOCTOR" && !t.awaitingDoctor && <p className="muted small">Every question has an answer.</p>}
      {canSend && (
        <form
          className="followup__form"
          onSubmit={(e) => {
            e.preventDefault();
            if (text.trim()) send.mutate();
          }}
        >
          <label htmlFor={`followup-${appointmentId}`} className="sr-only">
            {side === "PATIENT" ? "Your question" : "Your answer"}
          </label>
          <textarea
            id={`followup-${appointmentId}`}
            rows={2}
            maxLength={1000}
            value={text}
            onChange={(e) => setText(e.target.value)}
            placeholder={side === "PATIENT" ? "Ask about your treatment or results" : "Answer the patient"}
          />
          <button type="submit" className="button--sm" disabled={send.isPending || !text.trim()}>
            {send.isPending ? "Sending…" : side === "PATIENT" ? "Send question" : "Send answer"}
          </button>
        </form>
      )}
      {send.isError && (
        <p className="error" role="alert">
          {send.error instanceof ApiError ? send.error.message : "Could not send."}
        </p>
      )}
    </section>
  );
}
