import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../../api/client";
import { doctorAccount } from "../../api/endpoints";
import type { HoursWindow } from "../../api/types";
import { SLOT_LENGTHS, WEEKDAYS, slotsInDay, type HoursRow as Row } from "../../lib/doctors";

const KEY = ["doctor", "hours"];

const DEFAULT_ROW: Row = { on: false, startsAt: "10:00", endsAt: "13:00", slotMinutes: 30 };

/**
 * The week the doctor works. Saving opens bookable slots for the next four
 * weeks; slots someone already booked are kept whatever changes.
 */
export function HoursPage() {
  const hours = useQuery({ queryKey: KEY, queryFn: doctorAccount.hours });
  if (hours.isPending) return <div className="card skeleton" style={{ height: 320 }} />;
  if (hours.isError) return <p className="error">Could not load your hours.</p>;
  return <HoursEditor saved={hours.data} />;
}

function HoursEditor({ saved }: { saved: HoursWindow[] }) {
  const queryClient = useQueryClient();
  const [rows, setRows] = useState<Row[]>(() =>
    WEEKDAYS.map((_, i) => {
      const w = saved.find((h) => h.weekday === i + 1);
      return w ? { on: true, startsAt: w.startsAt.slice(0, 5), endsAt: w.endsAt.slice(0, 5), slotMinutes: w.slotMinutes } : DEFAULT_ROW;
    }),
  );
  const save = useMutation({
    mutationFn: () =>
      doctorAccount.saveHours(
        rows.flatMap((r, i) => (r.on ? [{ weekday: i + 1, startsAt: r.startsAt, endsAt: r.endsAt, slotMinutes: r.slotMinutes }] : [])),
      ),
    onSuccess: (result) => queryClient.setQueryData(KEY, result.hours),
  });

  function update(i: number, patch: Partial<Row>) {
    setRows((prev) => prev.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  }

  const perWeek = rows.reduce((n, r) => n + slotsInDay(r), 0);

  return (
    <div className="stack">
      <header>
        <h1 className="portal__title">Your hours</h1>
        <p className="muted">
          The days and times patients can book you. Saving opens appointments for the next four weeks, and every
          night another day is added. Appointments already booked are never removed.
        </p>
      </header>

      <form
        className="card"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <div className="table-wrap">
          <table className="rx__table hours">
            <thead>
              <tr>
                <th scope="col">Day</th>
                <th scope="col">From</th>
                <th scope="col">To</th>
                <th scope="col">Each visit</th>
                <th scope="col" className="num">
                  Visits
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={WEEKDAYS[i]} className={r.on ? undefined : "is-off"}>
                  <td>
                    <label className="check">
                      <input type="checkbox" checked={r.on} onChange={(e) => update(i, { on: e.target.checked })} />
                      {WEEKDAYS[i]}
                    </label>
                  </td>
                  <td>
                    <input
                      type="time"
                      aria-label={`${WEEKDAYS[i]} from`}
                      disabled={!r.on}
                      value={r.startsAt}
                      onChange={(e) => update(i, { startsAt: e.target.value })}
                    />
                  </td>
                  <td>
                    <input
                      type="time"
                      aria-label={`${WEEKDAYS[i]} to`}
                      disabled={!r.on}
                      value={r.endsAt}
                      onChange={(e) => update(i, { endsAt: e.target.value })}
                    />
                  </td>
                  <td>
                    <select
                      aria-label={`${WEEKDAYS[i]} visit length`}
                      disabled={!r.on}
                      value={r.slotMinutes}
                      onChange={(e) => update(i, { slotMinutes: Number(e.target.value) })}
                    >
                      {SLOT_LENGTHS.map((m) => (
                        <option key={m} value={m}>
                          {m} min
                        </option>
                      ))}
                    </select>
                  </td>
                  <td className="num">{slotsInDay(r) || "–"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <p className="muted small">{perWeek} appointments a week.</p>
        {save.isError && (
          <p className="error" role="alert">
            {save.error instanceof ApiError ? save.error.message : "Could not save your hours."}
          </p>
        )}
        {save.isSuccess && (
          <p className="notice notice--ok" role="status">
            Saved. {save.data.slotsOpened} appointments opened for the next four weeks.
          </p>
        )}
        <button type="submit" disabled={save.isPending}>
          {save.isPending ? "Saving…" : "Save hours"}
        </button>
      </form>
    </div>
  );
}
