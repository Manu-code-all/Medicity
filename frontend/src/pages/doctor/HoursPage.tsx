import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../../api/client";
import { doctorAccount } from "../../api/endpoints";
import type { HoursWindow } from "../../api/types";
import { formatDayLong } from "../../lib/format";
import { SLOT_LENGTHS, WEEKDAYS, slotsInDay } from "../../lib/doctors";

const KEY = ["doctor", "hours"];
const LEAVE_KEY = ["doctor", "leave"];
const MAX_SESSIONS = 3;

interface Session {
  startsAt: string;
  endsAt: string;
  slotMinutes: number;
}

interface Day {
  on: boolean;
  sessions: Session[];
}

const FIRST_SESSION: Session = { startsAt: "10:00", endsAt: "13:00", slotMinutes: 30 };

/** A later session starts an hour after the previous one ends: the usual lunch break. */
function nextSession(previous: Session): Session {
  const [h = 0, m = 0] = previous.endsAt.split(":").map(Number);
  const start = Math.min(h + 1, 21);
  const pad = (n: number) => String(n).padStart(2, "0");
  return { startsAt: `${pad(start)}:${pad(m)}`, endsAt: `${pad(Math.min(start + 3, 23))}:${pad(m)}`, slotMinutes: previous.slotMinutes };
}

function sessionLabel(day: string, index: number) {
  return index === 0 ? day : `${day} session ${index + 1}`;
}

/**
 * The week the doctor works, in up to three sessions a day (a lunch break,
 * two clinics), and the days off. Saving opens bookable slots for the next
 * four weeks; slots someone already booked are kept whatever changes.
 */
export function HoursPage() {
  const hours = useQuery({ queryKey: KEY, queryFn: doctorAccount.hours });
  if (hours.isPending) return <div className="card skeleton" style={{ height: 320 }} />;
  if (hours.isError) return <p className="error">Could not load your hours.</p>;
  return (
    <div className="stack">
      <header>
        <h1 className="portal__title">Your hours</h1>
        <p className="muted">
          The days and times patients can book you. Saving opens appointments for the next four weeks, and every
          night another day is added. Appointments already booked are never removed.
        </p>
      </header>
      <HoursEditor saved={hours.data} />
      <DaysOff />
    </div>
  );
}

function HoursEditor({ saved }: { saved: HoursWindow[] }) {
  const queryClient = useQueryClient();
  const [days, setDays] = useState<Day[]>(() =>
    WEEKDAYS.map((_, i) => {
      const windows = saved
        .filter((h) => h.weekday === i + 1)
        .map((w) => ({ startsAt: w.startsAt.slice(0, 5), endsAt: w.endsAt.slice(0, 5), slotMinutes: w.slotMinutes }));
      return windows.length ? { on: true, sessions: windows } : { on: false, sessions: [FIRST_SESSION] };
    }),
  );
  const save = useMutation({
    mutationFn: () =>
      doctorAccount.saveHours(
        days.flatMap((d, i) => (d.on ? d.sessions.map((s) => ({ weekday: i + 1, ...s })) : [])),
      ),
    onSuccess: (result) => queryClient.setQueryData(KEY, result.hours),
  });

  function updateDay(i: number, patch: (d: Day) => Day) {
    setDays((prev) => prev.map((d, j) => (j === i ? patch(d) : d)));
  }
  function updateSession(i: number, k: number, patch: Partial<Session>) {
    updateDay(i, (d) => ({ ...d, sessions: d.sessions.map((s, n) => (n === k ? { ...s, ...patch } : s)) }));
  }

  const perWeek = days.reduce(
    (n, d) => n + (d.on ? d.sessions.reduce((m, s) => m + slotsInDay({ on: true, ...s }), 0) : 0),
    0,
  );

  return (
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
              <th scope="col">
                <span className="sr-only">Sessions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {days.flatMap((d, i) =>
              d.sessions.map((s, k) => {
                const label = sessionLabel(WEEKDAYS[i] ?? "", k);
                const last = k === d.sessions.length - 1;
                return (
                  <tr key={`${WEEKDAYS[i]}-${k}`} className={[d.on ? "" : "is-off", k > 0 ? "hours__more" : ""].join(" ").trim() || undefined}>
                    <td>
                      {k === 0 ? (
                        <label className="check">
                          <input
                            type="checkbox"
                            checked={d.on}
                            onChange={(e) => updateDay(i, (day) => ({ ...day, on: e.target.checked }))}
                          />
                          {WEEKDAYS[i]}
                        </label>
                      ) : (
                        <span className="muted small">then</span>
                      )}
                    </td>
                    <td>
                      <input
                        type="time"
                        aria-label={`${label} from`}
                        disabled={!d.on}
                        value={s.startsAt}
                        onChange={(e) => updateSession(i, k, { startsAt: e.target.value })}
                      />
                    </td>
                    <td>
                      <input
                        type="time"
                        aria-label={`${label} to`}
                        disabled={!d.on}
                        value={s.endsAt}
                        onChange={(e) => updateSession(i, k, { endsAt: e.target.value })}
                      />
                    </td>
                    <td>
                      <select
                        aria-label={`${label} visit length`}
                        disabled={!d.on}
                        value={s.slotMinutes}
                        onChange={(e) => updateSession(i, k, { slotMinutes: Number(e.target.value) })}
                      >
                        {SLOT_LENGTHS.map((m) => (
                          <option key={m} value={m}>
                            {m} min
                          </option>
                        ))}
                      </select>
                    </td>
                    <td className="num">{(d.on && slotsInDay({ on: true, ...s })) || "–"}</td>
                    <td className="hours__actions">
                      {k > 0 && (
                        <button
                          type="button"
                          className="link"
                          aria-label={`Remove ${label}`}
                          onClick={() => updateDay(i, (day) => ({ ...day, sessions: day.sessions.filter((_, n) => n !== k) }))}
                        >
                          Remove
                        </button>
                      )}
                      {d.on && last && d.sessions.length < MAX_SESSIONS && (
                        <button
                          type="button"
                          className="link"
                          aria-label={`Add a session on ${WEEKDAYS[i]}`}
                          onClick={() =>
                            updateDay(i, (day) => ({ ...day, sessions: [...day.sessions, nextSession(s)] }))
                          }
                        >
                          + Session
                        </button>
                      )}
                    </td>
                  </tr>
                );
              }),
            )}
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
  );
}

function todayInIndia() {
  return new Date(Date.now() + 330 * 60_000).toISOString().slice(0, 10);
}

/** Days off: no appointments are opened; visits already booked stay and are counted. */
function DaysOff() {
  const queryClient = useQueryClient();
  const leave = useQuery({ queryKey: LEAVE_KEY, queryFn: doctorAccount.leave });
  const [day, setDay] = useState("");
  const [note, setNote] = useState("");
  const refresh = () => void queryClient.invalidateQueries({ queryKey: LEAVE_KEY });
  const add = useMutation({
    mutationFn: () => doctorAccount.addLeave(day, note),
    onSuccess: () => {
      setDay("");
      setNote("");
      refresh();
    },
  });
  const remove = useMutation({ mutationFn: doctorAccount.removeLeave, onSuccess: refresh });

  return (
    <section className="card stack" aria-labelledby="days-off">
      <div>
        <h2 id="days-off" className="portal__subtitle">
          Days off
        </h2>
        <p className="muted small">
          Leave, a conference, a holiday. Nobody can book you that day and the walk-in queue stays closed. Visits
          already booked are kept, so you can decide what to tell those patients.
        </p>
      </div>

      {leave.data && leave.data.length > 0 && (
        <ul className="days-off">
          {leave.data.map((l) => (
            <li key={l.day}>
              <span>
                <strong>{formatDayLong(`${l.day}T12:00:00+05:30`)}</strong>
                {l.note && <span className="muted"> · {l.note}</span>}
                {l.bookedVisits > 0 && (
                  <span className="days-off__warn">
                    {" "}
                    · {l.bookedVisits} {l.bookedVisits === 1 ? "visit is" : "visits are"} still booked that day
                  </span>
                )}
              </span>
              <button
                type="button"
                className="link"
                disabled={remove.isPending}
                aria-label={`Remove day off on ${l.day}`}
                onClick={() => remove.mutate(l.day)}
              >
                Remove
              </button>
            </li>
          ))}
        </ul>
      )}
      {leave.data && leave.data.length === 0 && <p className="muted small">No days off coming up.</p>}

      <form
        className="days-off__form"
        onSubmit={(e) => {
          e.preventDefault();
          if (day) add.mutate();
        }}
      >
        <label>
          Day
          <input type="date" min={todayInIndia()} value={day} onChange={(e) => setDay(e.target.value)} required />
        </label>
        <label>
          Note <span className="muted small">(optional, only you see it)</span>
          <input type="text" maxLength={120} value={note} onChange={(e) => setNote(e.target.value)} />
        </label>
        <button type="submit" disabled={add.isPending || !day}>
          {add.isPending ? "Saving…" : "Mark day off"}
        </button>
      </form>
      {add.isError && (
        <p className="error" role="alert">
          {add.error instanceof ApiError ? add.error.message : "Could not mark that day off."}
        </p>
      )}
      {add.isSuccess && add.data.bookedVisits > 0 && (
        <p className="notice" role="status">
          Marked. {add.data.bookedVisits} {add.data.bookedVisits === 1 ? "visit was" : "visits were"} already booked
          that day and {add.data.bookedVisits === 1 ? "is" : "are"} kept.
        </p>
      )}
    </section>
  );
}
