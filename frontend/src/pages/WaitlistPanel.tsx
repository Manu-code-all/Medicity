import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../api/client";
import { waitlist } from "../api/endpoints";
import type { WaitlistEntry } from "../api/types";

const KEY = ["waitlist", "mine"];

function isoDay(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

function dayLabel(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" });
}

/**
 * "The day I want is full: tell me if a time opens." The patient picks a day;
 * a cancellation or a move that day sends them a notification, and whoever
 * books first gets the time. The list shows what they are already waiting for
 * with this doctor, each with a way to stop.
 */
export function WaitlistPanel({ doctorId }: { doctorId: string }) {
  const queryClient = useQueryClient();
  const today = new Date();
  const max = new Date();
  max.setDate(max.getDate() + 60);
  const [date, setDate] = useState(isoDay(today));
  const [notice, setNotice] = useState<string | null>(null);

  const mine = useQuery({ queryKey: KEY, queryFn: waitlist.mine });
  const here = mine.data?.filter((w) => w.doctorId === doctorId) ?? [];

  const join = useMutation({
    mutationFn: (day: string) => waitlist.join(doctorId, day),
    // Shown at once; the server's answer replaces it (or the error removes it).
    onMutate: async (day) => {
      await queryClient.cancelQueries({ queryKey: KEY });
      const before = queryClient.getQueryData<WaitlistEntry[]>(KEY);
      queryClient.setQueryData<WaitlistEntry[]>(KEY, (list = []) =>
        list.some((w) => w.doctorId === doctorId && w.date === day)
          ? list
          : [...list, { id: `pending-${day}`, doctorId, doctorName: "", specialization: "", patientId: "", patientName: "", date: day, status: "ACTIVE" }],
      );
      return { before };
    },
    onError: (_e, _day, context) => queryClient.setQueryData(KEY, context?.before),
    onSuccess: (entry) => setNotice(`We'll tell you if a time opens on ${dayLabel(entry.date)}.`),
    onSettled: () => void queryClient.invalidateQueries({ queryKey: KEY }),
  });
  const leave = useMutation({
    mutationFn: (day: string) => waitlist.leave(doctorId, day),
    onSettled: () => void queryClient.invalidateQueries({ queryKey: KEY }),
  });

  return (
    <section id="waitlist" className="waitlist card" aria-labelledby="waitlist-title">
      <h2 id="waitlist-title">Is the day you want full?</h2>
      <p className="muted small">
        We'll notify you if a visit that day is cancelled or moved. Whoever books first gets the time.
      </p>
      <form
        className="waitlist__form"
        onSubmit={(e) => {
          e.preventDefault();
          setNotice(null);
          join.mutate(date);
        }}
      >
        <label htmlFor="waitlist-date">Day</label>
        <input
          id="waitlist-date"
          type="date"
          min={isoDay(today)}
          max={isoDay(max)}
          value={date}
          onChange={(e) => setDate(e.target.value)}
          required
        />
        <button type="submit" disabled={join.isPending}>
          <span aria-hidden="true">🔔</span> Notify me if a time opens
        </button>
      </form>
      {notice && (
        <p className="notice" role="status">
          {notice}
        </p>
      )}
      {join.isError && (
        <p className="error" role="alert">
          {join.error instanceof ApiError ? join.error.message : "Could not add you to the waiting list."}
        </p>
      )}
      {here.length > 0 && (
        <ul className="waitlist__list" aria-label="Days you are waiting for">
          {here.map((w) => (
            <li key={w.id}>
              <span>
                {dayLabel(w.date)}
                {w.patientName && <span className="muted small"> · for {w.patientName}</span>}
                {w.status === "NOTIFIED" && <span className="badge badge--video">A time opened</span>}
              </span>
              <button type="button" className="link" disabled={leave.isPending} onClick={() => leave.mutate(w.date)}>
                Stop waiting
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
