import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ArrowCounterClockwise } from "@phosphor-icons/react";
import { stores } from "../api/endpoints";
import { useAuth } from "../auth/context";
import { DEMO_HOME, DEMO_STORES, offsetKm, type Answer } from "../lib/demo";
import { formatDistance, type Point } from "../lib/geo";
import { prefersReducedMotion, useInView } from "../lib/reveal";

const W = 640;
const H = 560;
const HOME = { x: 300, y: 290 };
/** Pixels per kilometre in the drawing. */
const SCALE = 100;
const RADIUS_M = 3000;
/** The drawing has room for this many labelled stores before they crowd. */
const MAX_LIVE = 6;

interface Pin {
  key: string;
  name: string;
  x: number;
  y: number;
  distance: string;
  /** Which side of its marker the label sits, so labels do not collide. */
  side: "left" | "right";
  /** Only the example has answers: nobody has asked a real question yet. */
  answer?: Answer;
  open?: boolean;
}

const clamp = (n: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, n));

function place(lat: number, lng: number, origin: Point) {
  const { x, y } = offsetKm(lat, lng, origin);
  return { x: clamp(HOME.x + x * SCALE, 48, W - 48), y: clamp(HOME.y - y * SCALE, 40, H - 40) };
}

const EXAMPLE: Pin[] = DEMO_STORES.map((s) => ({
  key: s.name,
  name: s.name,
  ...place(s.lat, s.lng, DEMO_HOME),
  distance: s.distance,
  side: s.side,
  answer: s.answer,
}));

/** Where the signed-in person is, asked once, when the map is first seen. */
function useMyPosition(enabled: boolean) {
  const [point, setPoint] = useState<Point | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    if (!enabled || point || failed) return;
    if (!("geolocation" in navigator)) {
      setFailed(true);
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => setPoint({ lat: pos.coords.latitude, lng: pos.coords.longitude }),
      () => setFailed(true),
      { enableHighAccuracy: false, timeout: 10_000, maximumAge: 300_000 },
    );
  }, [enabled, point, failed]);
  return { point, failed };
}

/**
 * The mechanism, drawn: one question going out to every verified chemist
 * within 3 km and the answers coming back. Signed out it is an example with
 * invented-free seed data; signed in (any role) it is the chemists around the
 * person's own position, from the same directory the app searches.
 */
export function LineMap() {
  const { session } = useAuth();
  const [ref, seen] = useInView<HTMLElement>("0px");
  const mine = useMyPosition(Boolean(session) && seen);
  const nearby = useQuery({
    queryKey: ["landing", "nearby", mine.point?.lat, mine.point?.lng],
    queryFn: () => stores.nearby(mine.point!.lat, mine.point!.lng, RADIUS_M),
    enabled: Boolean(mine.point),
  });

  const live = Boolean(session) && Boolean(mine.point) && nearby.isSuccess;
  const pins: Pin[] = live
    ? nearby.data.slice(0, MAX_LIVE).map((s) => {
        const p = place(s.latitude, s.longitude, mine.point!);
        return {
          key: s.id,
          name: s.name,
          ...p,
          distance: formatDistance(s.distanceM),
          side: p.x < HOME.x ? "left" : "right",
          open: s.openNow,
        };
      })
    : EXAMPLE;
  const answering = pins.filter((p) => p.answer && p.answer.kind !== "waiting").length;

  // 0: nothing sent; 1: asked; 2+: that many answers back (the example only).
  const [step, setStep] = useState(0);
  const [run, setRun] = useState(0);
  const pinKey = pins.map((p) => p.key).join();

  useEffect(() => {
    if (!seen) return;
    if (prefersReducedMotion()) {
      setStep(1 + answering);
      return;
    }
    setStep(0);
    const timers = [window.setTimeout(() => setStep(1), 300)];
    for (let i = 1; i <= answering; i++) {
      timers.push(window.setTimeout(() => setStep(1 + i), 900 + i * 900));
    }
    return () => timers.forEach((t) => window.clearTimeout(t));
  }, [seen, run, answering, pinKey]);

  const answered = Math.max(0, step - 1);
  const done = answered === answering;

  return (
    <figure className="lm-map" ref={ref} data-asked={step >= 1}>
      <div className="lm-map__canvas">
        <svg viewBox={`0 0 ${W} ${H}`} aria-hidden="true" preserveAspectRatio="xMidYMid meet">
          {/* Streets are decoration for the example; a real position has none drawn. */}
          {!live && (
            <g className="lm-map__streets">
              <path d="M250 0 L300 280 L336 560" />
              <path d="M0 322 L640 372" />
              <path d="M0 468 L640 424" />
              <path d="M170 0 L640 118" />
              <path d="M0 150 L260 196" />
            </g>
          )}
          {/* The route in from the copy beside the map; the questions branch from home. */}
          <path className="lm-map__trunk" d={`M-64 ${HOME.y} L${HOME.x} ${HOME.y}`} />
          <circle className="lm-map__ring" cx={HOME.x} cy={HOME.y} r={SCALE} />
          <circle className="lm-map__ring lm-map__ring--outer" cx={HOME.x} cy={HOME.y} r={SCALE * 3} />
          <text className="lm-map__ring-label" x={HOME.x + 8} y={HOME.y - SCALE - 8}>1 km</text>
          <text className="lm-map__ring-label" x={HOME.x + 214} y={HOME.y + 214}>3 km</text>
          {pins.map((pin, i) => (
            <path
              key={pin.key}
              className="lm-map__ask"
              d={`M${HOME.x} ${HOME.y} L${pin.x} ${pin.y}`}
              pathLength={1}
              style={{ transitionDelay: `${i * 70}ms` }}
            />
          ))}
        </svg>

        <div className="lm-map__home" style={pct(HOME)}>
          <span className="lm-roundel lm-roundel--home" aria-hidden="true" />
          <span className="lm-map__home-label">{live ? "You are here" : "You"}</span>
        </div>

        {pins.map((pin, i) => {
          const back = pin.answer && pin.answer.kind !== "waiting" && i < answered;
          const cheapest = done && pin.name.startsWith("Lakshmi");
          return (
            <div
              key={pin.key}
              className={`lm-map__store lm-map__store--${pin.side}`}
              data-state={back && pin.answer ? pin.answer.kind : step >= 1 ? "asked" : "idle"}
              style={pct(pin)}
            >
              <span className="lm-roundel" aria-hidden="true" />
              <span className="lm-map__label">
                <strong>{pin.name}</strong>
                <span className="lm-map__detail">
                  {back && pin.answer && pin.answer.kind !== "waiting" ? (
                    <>
                      {pin.answer.detail} · <span className="lm-num">{pin.answer.price}</span>
                    </>
                  ) : live ? (
                    pin.open ? "Open now" : "Closed now"
                  ) : step >= 1 ? (
                    "Asked, waiting"
                  ) : (
                    "Verified store"
                  )}
                </span>
                <span className="lm-map__detail lm-num">{pin.distance} away</span>
                {cheapest && <span className="lm-map__tag">Cheapest</span>}
              </span>
            </div>
          );
        })}
      </div>

      {/* On phones the labels would crowd the map; the stores read as a list instead. */}
      <ol className="lm-map__answers">
        {pins
          .filter((p, i) => live || (p.answer && p.answer.kind !== "waiting" && i < answered))
          .map((pin) => (
            <li key={pin.key} data-state={pin.answer && pin.answer.kind !== "waiting" ? pin.answer.kind : "asked"}>
              <span className="lm-roundel" aria-hidden="true" />
              <span>
                <strong>{pin.name}</strong>
                <span className="lm-map__detail">
                  {pin.answer && pin.answer.kind !== "waiting" ? pin.answer.detail : pin.open ? "Open now" : "Closed now"}{" "}
                  · <span className="lm-num">{pin.distance}</span>
                </span>
              </span>
              {pin.answer && pin.answer.kind !== "waiting" && <span className="lm-num">{pin.answer.price}</span>}
            </li>
          ))}
      </ol>

      <figcaption className="lm-map__caption">
        <span aria-live="polite">
          {live
            ? pins.length === 0
              ? "No verified chemists within 3 km of you yet"
              : `${pins.length} verified ${pins.length === 1 ? "chemist" : "chemists"} within 3 km of you`
            : step === 0
              ? "Asking the verified chemists within 3 km"
              : `${pins.length} chemists asked for 14 omeprazole capsules · ${answered} answered`}
        </span>
        {!live && (
          <button type="button" className="lm-link-button" onClick={() => setRun((n) => n + 1)} disabled={!done}>
            <ArrowCounterClockwise size={16} weight="bold" aria-hidden="true" />
            Ask again
          </button>
        )}
      </figcaption>
      <p className="lm-map__note">
        {live
          ? "From your browser's location, which is not stored."
          : session && mine.failed
            ? "Allow location in your browser to see the chemists around you. Until then, an example."
            : session
              ? "Finding the chemists around you. Until then, an example."
              : "An example. Sign in to see the chemists around you."}
      </p>
    </figure>
  );
}

function pct(p: { x: number; y: number }) {
  return { left: `${(p.x / W) * 100}%`, top: `${(p.y / H) * 100}%` };
}
