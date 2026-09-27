import { useEffect, useState } from "react";
import { ArrowCounterClockwise } from "@phosphor-icons/react";
import { DEMO_STORES, offsetKm, type MapStore } from "../lib/demo";
import { prefersReducedMotion, useInView } from "../lib/reveal";

const W = 640;
const H = 560;
const HOME = { x: 300, y: 290 };
/** Pixels per kilometre in the drawing. */
const SCALE = 100;
const ANSWERING = DEMO_STORES.filter((s) => s.answer.kind !== "waiting").length;

function position(store: MapStore) {
  const { x, y } = offsetKm(store.lat, store.lng);
  return { x: HOME.x + x * SCALE, y: HOME.y - y * SCALE };
}

/**
 * The demo patient's question going out to every verified chemist within 3 km
 * of home, and the answers coming back one by one: the product's mechanism,
 * drawn from the same stores and prices the demo seeds.
 */
export function LineMap() {
  const [ref, seen] = useInView<HTMLElement>("0px");
  // 0: nothing sent; 1: asked; 2+: that many answers back.
  const [step, setStep] = useState(0);
  const [run, setRun] = useState(0);

  useEffect(() => {
    if (!seen) return;
    if (prefersReducedMotion()) {
      setStep(1 + ANSWERING);
      return;
    }
    setStep(0);
    const timers = [window.setTimeout(() => setStep(1), 300)];
    for (let i = 1; i <= ANSWERING; i++) {
      timers.push(window.setTimeout(() => setStep(1 + i), 900 + i * 900));
    }
    return () => timers.forEach((t) => window.clearTimeout(t));
  }, [seen, run]);

  const answered = Math.max(0, step - 1);
  const done = answered === ANSWERING;

  return (
    <figure className="lm-map" ref={ref} data-asked={step >= 1}>
      <div className="lm-map__canvas">
        <svg viewBox={`0 0 ${W} ${H}`} aria-hidden="true" preserveAspectRatio="xMidYMid meet">
          <g className="lm-map__streets">
            <path d="M250 0 L300 280 L336 560" />
            <path d="M0 322 L640 372" />
            <path d="M0 468 L640 424" />
            <path d="M170 0 L640 118" />
            <path d="M0 150 L260 196" />
          </g>
          {/* The route in from the copy beside the map; the questions branch from home. */}
          <path className="lm-map__trunk" d={`M-64 ${HOME.y} L${HOME.x} ${HOME.y}`} />
          <circle className="lm-map__ring" cx={HOME.x} cy={HOME.y} r={SCALE} />
          <circle className="lm-map__ring lm-map__ring--outer" cx={HOME.x} cy={HOME.y} r={SCALE * 3} />
          <text className="lm-map__ring-label" x={HOME.x + 8} y={HOME.y - SCALE - 8}>1 km</text>
          <text className="lm-map__ring-label" x={HOME.x + 214} y={HOME.y + 214}>3 km</text>
          {DEMO_STORES.map((store, i) => {
            const p = position(store);
            return (
              <path
                key={store.name}
                className="lm-map__ask"
                d={`M${HOME.x} ${HOME.y} L${p.x} ${p.y}`}
                pathLength={1}
                style={{ transitionDelay: `${i * 70}ms` }}
              />
            );
          })}
        </svg>

        <div className="lm-map__home" style={pct(HOME)}>
          <span className="lm-roundel lm-roundel--home" aria-hidden="true" />
          <span className="lm-map__home-label">Meera, Indiranagar</span>
        </div>

        {DEMO_STORES.map((store, i) => {
          const back = store.answer.kind !== "waiting" && i < answered;
          const cheapest = done && store.name.startsWith("Lakshmi");
          return (
            <div
              key={store.name}
              className={`lm-map__store lm-map__store--${store.side}`}
              data-state={back ? store.answer.kind : step >= 1 ? "asked" : "idle"}
              style={pct(position(store))}
            >
              <span className="lm-roundel" aria-hidden="true" />
              <span className="lm-map__label">
                <strong>{store.name}</strong>
                <span className="lm-map__detail">
                  {back && store.answer.kind !== "waiting" ? (
                    <>
                      {store.answer.detail} · <span className="lm-num">{store.answer.price}</span>
                    </>
                  ) : step >= 1 ? (
                    "Asked, waiting"
                  ) : (
                    "Verified store"
                  )}
                </span>
                <span className="lm-map__detail lm-num">{store.distance} away</span>
                {cheapest && <span className="lm-map__tag">Cheapest</span>}
              </span>
            </div>
          );
        })}
      </div>

      {/* On phones the labels would crowd the map; the answers read as a list instead. */}
      <ol className="lm-map__answers">
        {DEMO_STORES.slice(0, answered).map((store) =>
          store.answer.kind === "waiting" ? null : (
            <li key={store.name} data-state={store.answer.kind}>
              <span className="lm-roundel" aria-hidden="true" />
              <span>
                <strong>{store.name}</strong>
                <span className="lm-map__detail">
                  {store.answer.detail} · <span className="lm-num">{store.distance}</span>
                </span>
              </span>
              <span className="lm-num">{store.answer.price}</span>
            </li>
          ),
        )}
      </ol>

      <figcaption className="lm-map__caption">
        <span aria-live="polite">
          {step === 0
            ? "Asking the verified chemists within 3 km"
            : `5 chemists asked for 14 omeprazole capsules · ${answered} answered`}
        </span>
        <button
          type="button"
          className="lm-link-button"
          onClick={() => setRun((n) => n + 1)}
          disabled={!done}
        >
          <ArrowCounterClockwise size={16} weight="bold" aria-hidden="true" />
          Ask again
        </button>
      </figcaption>
      <p className="lm-map__note">Demo data from Indiranagar, Bengaluru. Streets are approximate.</p>
    </figure>
  );
}

function pct(p: { x: number; y: number }) {
  return { left: `${(p.x / W) * 100}%`, top: `${(p.y / H) * 100}%` };
}
