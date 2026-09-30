import { ActingBanner } from "../components/ActingBanner";
import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { ApiError } from "../api/client";
import { appointments, doctors, reviews } from "../api/endpoints";
import type { Slot, VisitType } from "../api/types";
import { readIntake, readVisitNote, saveIntake, saveVisitNote } from "../lib/visitNote";
import { WaitlistPanel } from "./WaitlistPanel";
import { AttachmentsPanel } from "../components/AttachmentsPanel";
import { ClinicLine } from "../components/ClinicLine";
import { initials } from "../lib/format";
import { useMyLocation } from "../lib/useMyLocation";

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
  // What the visitor typed in the body guide, as a starting point.
  const [reason, setReason] = useState(readVisitNote);
  const [visitType, setVisitType] = useState<VisitType>("IN_PERSON");
  // The body guide's answers, offered to the doctor; the patient decides.
  const [intake] = useState(readIntake);
  const [shareIntake, setShareIntake] = useState(true);
  const [notice, setNotice] = useState<string | null>(null);
  const [dayKey, setDayKey] = useState<string | null>(null);
  const me = useMyLocation();

  const window = useDateWindow();
  const doctor = useQuery({ queryKey: ["doctor", doctorId], queryFn: () => doctors.get(doctorId), staleTime: 60_000 });
  const doc = doctor.data;
  const doctorReviews = useQuery({ queryKey: ["reviews", doctorId], queryFn: () => reviews.forDoctor(doctorId) });
  // A time tapped on the directory card arrives as ?slot= and starts selected.
  const [params] = useSearchParams();
  const wanted = params.get("slot");
  const preselected = useRef(false);
  // Opened from "Change time" on a visit: this page moves that visit instead of booking a new one.
  const moving = params.get("move");

  const slotsQuery = useQuery({
    queryKey: ["slots", doctorId, window.from],
    queryFn: () => doctors.slots(doctorId, window.from, window.to),
    // Availability goes stale quickly under contention; a short window keeps
    // the list roughly honest without hammering the API.
    staleTime: 30_000,
  });

  // One key per booking attempt: the same slot and reason submitted again (a
  // double click, a retry after a dropped connection) reuses it, so the server
  // returns the booking it already made. Changing either starts a new attempt.
  // The visit type is part of the request, so changing it is a new attempt too.
  const attempt = useRef<{ slotId: string; why: string; key: string } | null>(null);
  function keyFor(slotId: string, why: string): string {
    if (attempt.current?.slotId !== slotId || attempt.current.why !== why) {
      attempt.current = { slotId, why, key: crypto.randomUUID() };
    }
    return attempt.current.key;
  }

  const booking = useMutation({
    mutationFn: ({ slotId, why, type, share }: { slotId: string; why: string; type: VisitType; share: boolean }) =>
      appointments.book(slotId, why, keyFor(slotId, `${type}:${share}:${why}`), type, share ? intake : null),
    // Safe only because of the key: a network failure may have hidden a
    // booking that succeeded, and the retry then returns it instead of
    // booking twice. Errors the server answered are not retried.
    retry: (failures, error) => !(error instanceof ApiError) && failures < 2,

    onSuccess: () => {
      attempt.current = null;
      saveVisitNote("");
      saveIntake(null);
      setNotice("Appointment confirmed.");
      setSelectedSlot(null);
      setReason("");
      void queryClient.invalidateQueries({ queryKey: ["slots", doctorId] });
      // Every portal view (next visit, counts, lists) now has a new row.
      void queryClient.invalidateQueries({ queryKey: ["portal"] });
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

  const move = useMutation({
    mutationFn: (slotId: string) => appointments.reschedule(moving!, slotId),
    onSuccess: (moved) => {
      setNotice(`Visit moved to ${formatSlot(moved.scheduledAt)}.`);
      setSelectedSlot(null);
      void queryClient.invalidateQueries({ queryKey: ["slots", doctorId] });
      void queryClient.invalidateQueries({ queryKey: ["portal"] });
    },
    onError: (error: unknown) => {
      if (!(error instanceof ApiError)) {
        setNotice("Something went wrong. Your visit has not been moved.");
        return;
      }
      if (error.code === "SLOT_ALREADY_BOOKED" || error.code === "SLOT_NOT_OPEN") {
        setNotice("Someone just took that time. Your visit is unchanged; here are the times still free.");
        setSelectedSlot(null);
        void queryClient.invalidateQueries({ queryKey: ["slots", doctorId] });
        return;
      }
      setNotice(`${error.message} Your visit has not been moved.`);
    },
  });

  useEffect(() => {
    if (!wanted || !slotsQuery.data || preselected.current) return;
    preselected.current = true;
    const slot = slotsQuery.data.find((s) => s.id === wanted);
    if (slot) setSelectedSlot(slot);
    else setNotice("That time has just been taken. Here are the times still free.");
  }, [wanted, slotsQuery.data]);

  if (slotsQuery.isPending) return <p className="muted">Loading availability…</p>;
  if (slotsQuery.isError) return <p className="error">Could not load availability.</p>;

  const slots = slotsQuery.data;
  const days = groupSlotsByDay(slots);
  // The day being looked at: the chosen time's day, else the one picked, else the first with times.
  const activeKey = dayKey && days.some((d) => d.key === dayKey) ? dayKey : selectedSlot ? keyOf(selectedSlot.startsAt) : days[0]?.key;
  const activeDay = days.find((d) => d.key === activeKey);
  const everyVisit = (doc?.prices ?? []).filter((p) => p.everyVisit).reduce((sum, p) => sum + p.priceInr, 0);

  return (
    <section className="booking">
      <header className="booking__head">
        <h1>{moving ? "Choose a new time" : "Book a visit"}</h1>
        {moving && <p className="muted">Your current time stays booked until the new one is confirmed.</p>}
      </header>
      <ActingBanner verb="Booking" />

      {doc && (
        <div className="booking__doctor">
          <div className="avatar avatar--lg" aria-hidden="true">
            {initials(doc.fullName)}
          </div>
          <div className="booking__who">
            <h2>{doc.fullName}</h2>
            <p className="muted">
              {doc.specialization} · {doc.yearsExperience} yrs experience
            </p>
            <ClinicLine doctor={doc} from={me.point} />
          </div>
          <div className="dcard__fee">
            <span className="dcard__feeLabel">Consultation</span>
            <p className="fee num">₹{doc.consultationFee + everyVisit}</p>
          </div>
        </div>
      )}

      {notice && (
        <p className="notice" role="status" aria-live="polite">
          {notice}
          {(booking.isSuccess || move.isSuccess) && (
            <>
              {" "}
              <Link to="/portal/visits">See it in your portal</Link>
            </>
          )}
        </p>
      )}

      <div className="booking__layout">
        <div className="booking__pick">
          {slots.length === 0 ? (
            <p className="muted">No open slots in the next 14 days.</p>
          ) : (
            <>
              <h2 className="booking__step">
                <span className="booking__n">1</span> Choose a day
              </h2>
              <div className="daystrip" role="tablist" aria-label="Days with open times">
                {days.map((d) => (
                  <button
                    key={d.key}
                    type="button"
                    role="tab"
                    aria-selected={d.key === activeKey}
                    className="daystrip__day"
                    onClick={() => setDayKey(d.key)}
                  >
                    <span className="daystrip__name">{d.name}</span>
                    <span className="daystrip__date num">{d.date}</span>
                    <span className="daystrip__count">
                      {d.slots.length} {d.slots.length === 1 ? "time" : "times"}
                    </span>
                  </button>
                ))}
              </div>

              <h2 className="booking__step">
                <span className="booking__n">2</span> Choose a time
              </h2>
              {activeDay &&
                PARTS.map((part) => {
                  const inPart = activeDay.slots.filter(part.test);
                  if (inPart.length === 0) return null;
                  return (
                    <div key={part.name} className="timepart">
                      <h3>{part.name}</h3>
                      <ul className="timegrid">
                        {inPart.map((slot) => (
                          <li key={slot.id}>
                            <button
                              type="button"
                              className={slot.id === selectedSlot?.id ? "time time--selected" : "time"}
                              aria-pressed={slot.id === selectedSlot?.id}
                              aria-label={formatSlot(slot.startsAt)}
                              onClick={() => setSelectedSlot(slot)}
                            >
                              {timeLabel(slot.startsAt)}
                            </button>
                          </li>
                        ))}
                      </ul>
                    </div>
                  );
                })}
            </>
          )}
          {!moving && <WaitlistPanel doctorId={doctorId} />}
        </div>

        <aside className="booking__summary" aria-label="Your visit">
          <h2 className="booking__step">
            <span className="booking__n">3</span> {moving ? "Confirm" : "Your visit"}
          </h2>
          {!selectedSlot ? (
            <p className="muted">Pick a day and a time and your visit appears here.</p>
          ) : (
            <p className="summary__when">
              <strong>
                {new Date(selectedSlot.startsAt).toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "long" })}
              </strong>
              <span className="num">{timeLabel(selectedSlot.startsAt)}</span>
            </p>
          )}

          {selectedSlot && moving && (
            <form
              className="booking__confirm"
              onSubmit={(e) => {
                e.preventDefault();
                setNotice(null);
                move.mutate(selectedSlot.id);
              }}
            >
              <button type="submit" disabled={move.isPending}>
                {move.isPending ? "Moving…" : `Move to ${formatSlot(selectedSlot.startsAt)}`}
              </button>
            </form>
          )}

          {selectedSlot && !moving && (
            <form
              className="booking__confirm"
              onSubmit={(e) => {
                e.preventDefault();
                setNotice(null);
                booking.mutate({ slotId: selectedSlot.id, why: reason, type: visitType, share: Boolean(intake && shareIntake) });
              }}
            >
              <fieldset className="visit-type">
                <legend>How would you like to see the doctor?</legend>
                <label>
                  <input
                    type="radio"
                    name="visit-type"
                    checked={visitType === "IN_PERSON"}
                    onChange={() => setVisitType("IN_PERSON")}
                  />{" "}
                  At the clinic
                </label>
                <label>
                  <input type="radio" name="visit-type" checked={visitType === "VIDEO"} onChange={() => setVisitType("VIDEO")} />{" "}
                  Video call
                </label>
              </fieldset>
              {intake && (
                <div className="intake-share">
                  <p className="intake-share__title">Your answers in the body guide</p>
                  <p className="muted small">
                    {[intake.area, ...intake.symptoms, intake.since].filter(Boolean).join(" · ")}
                  </p>
                  <label>
                    <input type="checkbox" checked={shareIntake} onChange={(e) => setShareIntake(e.target.checked)} /> Share
                    these answers with the doctor before the visit
                  </label>
                </div>
              )}
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
        </aside>
      </div>

      {booking.isSuccess && booking.data && (
        <section className="card" aria-labelledby="attach-title">
          <h2 id="attach-title">Have lab reports or earlier prescriptions?</h2>
          <p className="muted small">Attach them now so the doctor can read them before your visit (optional).</p>
          <AttachmentsPanel appointmentId={booking.data.id} canEdit />
        </section>
      )}

      {doctorReviews.data && doctorReviews.data.length > 0 && (
        <section id="reviews" className="reviews" aria-labelledby="reviews-title">
          <h2 id="reviews-title">What patients said</h2>
          <p className="muted small">Only patients who were seen can leave a review.</p>
          <ul>
            {doctorReviews.data.map((r, i) => (
              <li key={i} className="review">
                <span className="review__stars" aria-label={`${r.rating} out of 5`}>
                  {"★".repeat(r.rating)}
                  <span className="review__stars-off">{"★".repeat(5 - r.rating)}</span>
                </span>
                {r.comment && <p>{r.comment}</p>}
                <p className="muted small">{r.reviewer}</p>
              </li>
            ))}
          </ul>
        </section>
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

interface SlotDay {
  key: string;
  name: string;
  date: string;
  slots: Slot[];
}

function keyOf(iso: string): string {
  const d = new Date(iso);
  return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
}

/** The days that have open times, at most 14, each with its times in order. */
function groupSlotsByDay(slots: Slot[]): SlotDay[] {
  const days: SlotDay[] = [];
  for (const slot of slots) {
    const key = keyOf(slot.startsAt);
    let day = days.find((d) => d.key === key);
    if (!day) {
      const at = new Date(slot.startsAt);
      day = {
        key,
        name: at.toLocaleDateString(undefined, { weekday: "short" }),
        date: at.toLocaleDateString(undefined, { day: "numeric", month: "short" }),
        slots: [],
      };
      days.push(day);
    }
    day.slots.push(slot);
  }
  return days.slice(0, 14);
}

/** Morning, afternoon and evening read faster than one long run of times. */
const PARTS = [
  { name: "Morning", test: (s: Slot) => new Date(s.startsAt).getHours() < 12 },
  { name: "Afternoon", test: (s: Slot) => new Date(s.startsAt).getHours() >= 12 && new Date(s.startsAt).getHours() < 17 },
  { name: "Evening", test: (s: Slot) => new Date(s.startsAt).getHours() >= 17 },
];

function timeLabel(iso: string): string {
  return new Date(iso).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });
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
