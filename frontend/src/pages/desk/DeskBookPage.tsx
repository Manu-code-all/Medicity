import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { ApiError } from "../../api/client";
import { desk, doctors as doctorsApi } from "../../api/endpoints";
import type { DeskPatientRef } from "../../api/types";
import { addDays } from "../../lib/dates";
import { formatDate, formatTime } from "../../lib/format";
import { DESK_TODAY_KEY } from "../../lib/clinicKeys";
import { PatientPicker } from "./PatientPicker";

const DAYS_AHEAD = 14;

/** Books a visit for someone on the phone: pick a doctor and an open time, say who it is for. */
export function DeskBookPage() {
  const queryClient = useQueryClient();
  const today = useQuery({ queryKey: [...DESK_TODAY_KEY, "today"], queryFn: () => desk.today() });
  const [doctorId, setDoctorId] = useState("");
  const [slotId, setSlotId] = useState("");
  const [reason, setReason] = useState("");
  const [patient, setPatient] = useState<DeskPatientRef | null>(null);

  const slots = useQuery({
    queryKey: ["desk", "slots", doctorId],
    queryFn: () => {
      const now = new Date();
      return doctorsApi.slots(doctorId, now.toISOString(), addDays(now, DAYS_AHEAD).toISOString());
    },
    enabled: doctorId !== "",
  });

  const book = useMutation({
    mutationFn: () => desk.book(slotId, patient!, reason.trim() || undefined),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: DESK_TODAY_KEY });
      void queryClient.invalidateQueries({ queryKey: ["desk", "slots"] });
    },
  });

  const ready = slotId !== "" && patient !== null;

  function reset() {
    book.reset();
    setSlotId("");
    setReason("");
  }

  return (
    <div className="stack">
      <header>
        <h1 className="portal__title">Book a visit</h1>
        <p className="muted">For someone on the phone or at the counter. They are told by email if they have an account.</p>
      </header>

      {book.isSuccess ? (
        <div className="notice notice--ok" role="status">
          <p>
            <strong>
              Booked {book.data.patientName} with {book.data.doctorName}
            </strong>{" "}
            on {formatDate(book.data.scheduledAt)} at {formatTime(book.data.scheduledAt)}.
          </p>
          <div className="rx-form__actions">
            <button type="button" onClick={reset}>
              Book another
            </button>
            <Link to="/desk">Back to today</Link>
          </div>
        </div>
      ) : (
        <form
          className="card rx-form"
          onSubmit={(e) => {
            e.preventDefault();
            if (ready) book.mutate();
          }}
        >
          <label htmlFor="book-doctor">Doctor</label>
          <select
            id="book-doctor"
            value={doctorId}
            onChange={(e) => {
              setDoctorId(e.target.value);
              setSlotId("");
            }}
          >
            <option value="">Choose a doctor</option>
            {today.data?.doctors.map((d) => (
              <option key={d.id} value={d.id}>
                {d.fullName} ({d.specialization})
              </option>
            ))}
          </select>

          {doctorId && (
            <>
              <label htmlFor="book-slot">Time</label>
              {slots.isPending && <p className="muted">Loading times…</p>}
              {slots.data?.length === 0 && <p className="muted">No open times in the next {DAYS_AHEAD} days.</p>}
              {slots.data && slots.data.length > 0 && (
                <select id="book-slot" value={slotId} onChange={(e) => setSlotId(e.target.value)}>
                  <option value="">Choose a time</option>
                  {slots.data.map((s) => (
                    <option key={s.id} value={s.id}>
                      {formatDate(s.startsAt)}, {formatTime(s.startsAt)}
                    </option>
                  ))}
                </select>
              )}
            </>
          )}

          <PatientPicker onChange={setPatient} />

          <label htmlFor="book-reason">Reason (optional)</label>
          <input id="book-reason" maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />

          {book.isError && (
            <p className="error" role="alert">
              {book.error instanceof ApiError ? book.error.message : "Could not book."}
            </p>
          )}
          <div className="rx-form__actions">
            <button type="submit" disabled={!ready || book.isPending}>
              {book.isPending ? "Booking…" : "Book the visit"}
            </button>
          </div>
        </form>
      )}
    </div>
  );
}
