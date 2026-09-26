import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";
import { ApiError } from "../../api/client";
import { workspace } from "../../api/endpoints";
import type { DoctorVisitDetail, PrescriptionDraft } from "../../api/types";
import { formatDate, formatDayLong, formatTime } from "../../lib/format";
import { PrescriptionForm } from "./PrescriptionForm";

type Mode = "view" | "prescribe" | "correct";

export function VisitPage() {
  const { visitId = "" } = useParams();
  const queryClient = useQueryClient();
  const [mode, setMode] = useState<Mode>("view");
  const [actionError, setActionError] = useState<string | null>(null);

  const visit = useQuery({ queryKey: ["workspace", "visit", visitId], queryFn: () => workspace.visit(visitId) });

  // Every action returns the updated visit, so the page updates from the
  // response instead of refetching; the schedule is refreshed in the background.
  function onUpdated(updated: DoctorVisitDetail) {
    queryClient.setQueryData(["workspace", "visit", visitId], updated);
    void queryClient.invalidateQueries({ queryKey: ["workspace", "visits"] });
    setActionError(null);
    setMode("view");
  }
  function onFailed(error: unknown) {
    setActionError(error instanceof ApiError ? error.message : "Something went wrong. Please try again.");
    // A 409 means the visit changed under us (the patient cancelled, a
    // colleague acted): show the current state rather than a stale one.
    if (error instanceof ApiError && error.status === 409) {
      void queryClient.invalidateQueries({ queryKey: ["workspace", "visit", visitId] });
    }
  }

  const complete = useMutation({ mutationFn: () => workspace.complete(visitId), onSuccess: onUpdated, onError: onFailed });
  const noShow = useMutation({ mutationFn: () => workspace.noShow(visitId), onSuccess: onUpdated, onError: onFailed });
  const prescribe = useMutation({
    mutationFn: (draft: PrescriptionDraft) => workspace.prescribe(visitId, draft),
    onSuccess: onUpdated,
    onError: onFailed,
  });
  const correct = useMutation({
    mutationFn: ({ id, draft }: { id: string; draft: PrescriptionDraft }) => workspace.correct(id, draft),
    onSuccess: onUpdated,
    onError: onFailed,
  });

  if (visit.isPending) return <div className="card skeleton" style={{ height: 240 }} />;
  if (visit.isError) {
    return (
      <p className="error">
        {visit.error instanceof ApiError && visit.error.status === 403
          ? "This visit is not on your calendar."
          : "Could not load this visit."}
      </p>
    );
  }

  const v = visit.data;
  const rx = v.prescription;
  const formError = actionError;
  const fieldErrors =
    (prescribe.error instanceof ApiError && prescribe.error.fieldErrors) ||
    (correct.error instanceof ApiError && correct.error.fieldErrors) ||
    {};

  return (
    <div className="stack">
      <p>
        <Link to="/doctor">← Schedule</Link>
      </p>

      <section className="card visit-head">
        <div>
          <p className="eyebrow">
            {formatDayLong(v.scheduledAt)} · {formatTime(v.scheduledAt)}–{formatTime(v.endsAt)}
          </p>
          <h1 className="portal__title">{v.patient.fullName}</h1>
          <p className="muted">
            {v.patient.age} yrs · {v.patient.gender.toLowerCase()}
            {v.patient.bloodGroup && ` · blood group ${v.patient.bloodGroup}`}
          </p>
          {v.reason && (
            <p className="visit-head__reason">
              <strong>Reason: </strong>
              {v.reason}
            </p>
          )}
          {v.cancelReason && <p className="muted">Cancelled by patient: {v.cancelReason}</p>}
          <Link to={`/doctor/patients/${v.patient.id}`}>View patient history →</Link>
        </div>
        <span className={`badge badge--${v.status.toLowerCase()}`}>{v.status.replace("_", " ").toLowerCase()}</span>
      </section>

      {mode === "view" && actionError && (
        <p className="error" role="alert">
          {actionError}
        </p>
      )}

      {v.status === "BOOKED" && (
        <section className="card">
          <h2 className="portal__subtitle">Close this visit</h2>
          {v.started ? (
            <div className="actions">
              <button type="button" disabled={complete.isPending} onClick={() => complete.mutate()}>
                {complete.isPending ? "Saving…" : "Patient was seen"}
              </button>
              <button
                type="button"
                className="button--ghost"
                disabled={noShow.isPending}
                onClick={() => noShow.mutate()}
              >
                Patient did not come
              </button>
            </div>
          ) : (
            <p className="muted">You can close this visit once it starts at {formatTime(v.scheduledAt)}.</p>
          )}
        </section>
      )}

      {v.status === "NO_SHOW" && (
        <section className="card">
          <h2 className="portal__subtitle">Marked as missed</h2>
          <p className="muted">
            Visits that are not closed within a day of ending are marked missed automatically. If the patient was
            seen, record it here.
          </p>
          <div className="actions">
            <button
              type="button"
              className="button--ghost"
              disabled={complete.isPending}
              onClick={() => complete.mutate()}
            >
              {complete.isPending ? "Saving…" : "Patient was seen after all"}
            </button>
          </div>
        </section>
      )}

      {v.status === "COMPLETED" && (
        <section className="card">
          <div className="section__head">
            <h2 className="portal__subtitle">Prescription</h2>
            {rx && mode === "view" && (
              <button type="button" className="link" onClick={() => setMode("correct")}>
                Correct prescription
              </button>
            )}
          </div>

          {mode === "view" && !rx && (
            <div className="empty-inline">
              <p className="muted">No prescription yet.</p>
              <button type="button" onClick={() => setMode("prescribe")}>
                Write prescription
              </button>
            </div>
          )}

          {mode === "view" && rx && (
            <div className="rx-view">
              <p className="eyebrow">
                Issued {formatDate(rx.issuedAt)}
                {rx.revised && " · revised"}
                {rx.dispensedAt ? ` · dispensed ${formatDate(rx.dispensedAt)}` : " · not yet dispensed"}
              </p>
              <h3>{rx.diagnosis}</h3>
              <ul>
                {rx.items.map((item) => (
                  <li key={item.medicine}>
                    <strong>
                      {item.medicine} {item.strength}
                    </strong>{" "}
                    — {item.dosage}, {item.frequency.toLowerCase()}, {item.durationDays} days ({item.quantity})
                  </li>
                ))}
              </ul>
              {rx.notes && <p className="rx__notes">{rx.notes}</p>}
            </div>
          )}

          {mode === "prescribe" && (
            <PrescriptionForm
              submitLabel="Issue prescription"
              busy={prescribe.isPending}
              error={formError}
              fieldErrors={fieldErrors}
              onSubmit={(draft) => prescribe.mutate(draft)}
              onCancel={() => {
                setMode("view");
                setActionError(null);
              }}
            />
          )}

          {mode === "correct" && rx && (
            <>
              <p className="notice">
                The correction replaces this prescription for the patient and the pharmacy. The original is kept
                on record.
              </p>
              <PrescriptionForm
                replacing={rx}
                submitLabel="Issue correction"
                busy={correct.isPending}
                error={formError}
                fieldErrors={fieldErrors}
                onSubmit={(draft) => correct.mutate({ id: rx.id, draft })}
                onCancel={() => {
                  setMode("view");
                  setActionError(null);
                }}
              />
            </>
          )}
        </section>
      )}
    </div>
  );
}
