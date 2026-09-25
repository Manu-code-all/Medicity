import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useParams } from "react-router-dom";
import { ApiError } from "../api/client";
import { appointments, doctors } from "../api/endpoints";
import type { Slot } from "../api/types";

/**
 * Slot picker and booking flow.
 *
 * The interesting behaviour here is what happens when the booking loses a race.
 * The slot list is a snapshot: between the moment it renders and the moment the
 * patient clicks, another patient may have taken the slot. The server settles
 * that authoritatively and returns 409 `SLOT_ALREADY_BOOKED`.
 *
 * The UI treats that as a normal outcome rather than an error state — it
 * explains what happened and refetches the list, so the taken slot disappears
 * and the patient can pick another without reloading the page.
 */
export function BookingPage() {
  const { doctorId = "" } = useParams();
  const queryClient = useQueryClient();

  const [selectedSlot, setSelectedSlot] = useState<Slot | null>(null);
  const [reason, setReason] = useState("");
  const [notice, setNotice] = useState<string | null>(null);

  const window = useDateWindow();

  const slotsQuery = useQuery({
    queryKey: ["slots", doctorId, window.from],
    queryFn: () => doctors.slots(doctorId, window.from, window.to),
    // Availability goes stale quickly under contention; a short window keeps
    // the list roughly honest without hammering the API.
    staleTime: 30_000,
  });

  const booking = useMutation({
    mutationFn: ({ slotId, why }: { slotId: string; why: string }) =>
      appointments.book(slotId, why),

    onSuccess: () => {
      setNotice("Appointment confirmed.");
      setSelectedSlot(null);
      setReason("");
      void queryClient.invalidateQueries({ queryKey: ["slots", doctorId] });
      void queryClient.invalidateQueries({ queryKey: ["my-appointments"] });
    },

    onError: (error: unknown) => {
      if (!(error instanceof ApiError)) {
        setNotice("Something went wrong. Please try again.");
        return;
      }

      // Branch on the stable code, never on the message text.
      switch (error.code) {
        case "SLOT_ALREADY_BOOKED":
          setNotice("Someone just booked that time. Here are the slots still free.");
          setSelectedSlot(null);
          void queryClient.invalidateQueries({ queryKey: ["slots", doctorId] });
          break;
        case "PATIENT_DOUBLE_BOOKED":
          setNotice("You already have an appointment at that time.");
          break;
        case "SLOT_TOO_SOON":
          setNotice("Appointments need at least 30 minutes' notice. Please pick a later slot.");
          break;
        case "SLOT_NOT_OPEN":
          setNotice("That slot is no longer available.");
          void queryClient.invalidateQueries({ queryKey: ["slots", doctorId] });
          break;
        default:
          setNotice(error.message);
      }
    },
  });

  if (slotsQuery.isPending) return <p className="muted">Loading availability…</p>;
  if (slotsQuery.isError) return <p className="error">Could not load availability.</p>;

  const slots = slotsQuery.data;

  return (
    <section className="booking">
      <h1>Choose a time</h1>

      {notice && (
        <p className="notice" role="status" aria-live="polite">
          {notice}
        </p>
      )}

      {slots.length === 0 ? (
        <p className="muted">No open slots in the next 14 days.</p>
      ) : (
        <ul className="slot-grid">
          {slots.map((slot) => (
            <li key={slot.id}>
              <button
                type="button"
                className={slot.id === selectedSlot?.id ? "slot slot--selected" : "slot"}
                aria-pressed={slot.id === selectedSlot?.id}
                onClick={() => setSelectedSlot(slot)}
              >
                {formatSlot(slot.startsAt)}
              </button>
            </li>
          ))}
        </ul>
      )}

      {selectedSlot && (
        <form
          className="booking__confirm"
          onSubmit={(e) => {
            e.preventDefault();
            setNotice(null);
            booking.mutate({ slotId: selectedSlot.id, why: reason });
          }}
        >
          <label htmlFor="reason">What brings you in?</label>
          <textarea
            id="reason"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            maxLength={500}
            rows={3}
          />
          <button type="submit" disabled={booking.isPending}>
            {booking.isPending ? "Booking…" : `Confirm ${formatSlot(selectedSlot.startsAt)}`}
          </button>
        </form>
      )}
    </section>
  );
}

/** The next 14 days, as an ISO window the API can filter on. */
function useDateWindow() {
  const [window] = useState(() => {
    const from = new Date();
    const to = new Date(from.getTime() + 14 * 24 * 60 * 60 * 1000);
    return { from: from.toISOString(), to: to.toISOString() };
  });
  return window;
}

function formatSlot(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    weekday: "short",
    day: "numeric",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
  });
}
