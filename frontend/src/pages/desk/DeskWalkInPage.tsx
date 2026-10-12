import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { ApiError } from "../../api/client";
import { desk } from "../../api/endpoints";
import type { DeskPatientRef } from "../../api/types";
import { DESK_TODAY_KEY } from "../../lib/clinicKeys";
import { PatientPicker } from "./PatientPicker";

/** Gives someone standing at the counter a walk-in token with any of the clinic's doctors. */
export function DeskWalkInPage() {
  const queryClient = useQueryClient();
  const today = useQuery({ queryKey: [...DESK_TODAY_KEY, "today"], queryFn: () => desk.today() });
  const [doctorId, setDoctorId] = useState("");
  const [reason, setReason] = useState("");
  const [patient, setPatient] = useState<DeskPatientRef | null>(null);

  const add = useMutation({
    mutationFn: () => desk.walkIn(doctorId, patient!, reason.trim() || undefined),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: DESK_TODAY_KEY }),
  });

  const doctors = today.data?.doctors ?? [];
  const ready = doctorId !== "" && patient !== null;

  return (
    <div className="stack">
      <header>
        <h1 className="portal__title">Walk-in</h1>
        <p className="muted">A token in the doctor's line for today, as if the person had taken one on their phone.</p>
      </header>

      {add.isSuccess ? (
        <div className="notice notice--ok" role="status">
          <p>
            <strong>
              Token #{add.data.tokenNo} for {add.data.patientName}
            </strong>{" "}
            with {add.data.doctorName}. About {add.data.estimatedWaitMinutes} minutes.
          </p>
          <div className="rx-form__actions">
            <button type="button" onClick={() => add.reset()}>
              Add another
            </button>
            <Link to="/desk">Back to today</Link>
          </div>
        </div>
      ) : (
        <form
          className="card rx-form"
          onSubmit={(e) => {
            e.preventDefault();
            if (ready) add.mutate();
          }}
        >
          <label htmlFor="walk-doctor">Doctor</label>
          <select id="walk-doctor" value={doctorId} onChange={(e) => setDoctorId(e.target.value)}>
            <option value="">Choose a doctor</option>
            {doctors.map((d) => (
              <option key={d.id} value={d.id} disabled={!d.walkInsOpen}>
                {d.fullName} ({d.specialization}){d.walkInsOpen ? `, ${d.waiting} waiting` : ", walk-ins closed"}
              </option>
            ))}
          </select>

          <PatientPicker onChange={setPatient} />

          <label htmlFor="walk-reason">Reason (optional)</label>
          <input id="walk-reason" maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)} />

          {add.isError && (
            <p className="error" role="alert">
              {add.error instanceof ApiError ? add.error.message : "Could not add the walk-in."}
            </p>
          )}
          <div className="rx-form__actions">
            <button type="submit" disabled={!ready || add.isPending}>
              {add.isPending ? "Adding…" : "Give a token"}
            </button>
          </div>
        </form>
      )}
    </div>
  );
}
