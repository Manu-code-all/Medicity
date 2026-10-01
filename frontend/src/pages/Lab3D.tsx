import { lazy, Suspense, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import Lenis from "lenis";
import { BodyMapCanvas } from "../components/bodymap/BodyMapCanvas";
import type { BodyView } from "../components/bodymap/taxonomy";
import { BODY_TAXONOMY } from "../components/bodymap/taxonomy";
import { useCan3D } from "../three/capability";
import "./lab3d.css";

const JourneyCanvas = lazy(() => import("../three/JourneyCanvas"));
const BodyMap3D = lazy(() => import("../three/BodyMap3D").then((m) => ({ default: m.BodyMap3D })));
const CityMap3D = lazy(() => import("../three/CityMap3D").then((m) => ({ default: m.CityMap3D })));

const CHAPTERS = [
  { name: "Book", title: "A slot that is yours the moment you take it", text: "Real open times for the next two weeks, the clinic and how far it is from you." },
  { name: "Visit", title: "Counted down in your portal, free to cancel", text: "Your next visit waits at the top of your portal, with your parents' and children's beside it." },
  { name: "Prescription", title: "Written by your doctor, kept current", text: "A correction replaces the old prescription, never two versions. How to take each medicine, in your language." },
  { name: "Chemists nearby", title: "Ask them all at once, compare the answers", text: "One tap sends the prescription to every verified chemist within 3 km. See who has it, the price and the distance." },
  { name: "Pick up", title: "Kept aside, handed over against your code", text: "Show your six digit code at the counter and pay there. Before you run out, Medicity reminds you to ask again." },
];

/**
 * A preview of the 3D direction, not linked from anywhere: the scroll journey
 * along the line, the body you turn, and the map of chemists. Nothing here
 * changes the live pages. Devices that cannot or should not draw 3D get the
 * flat versions of each piece, which are the real ones.
 */
export function Lab3D() {
  const can3D = useCan3D();
  const progress = useRef(0);
  const journey = useRef<HTMLDivElement>(null);
  const [view, setView] = useState<BodyView>("front");
  const [region, setRegion] = useState<string | null>(null);

  useEffect(() => {
    document.title = "3D preview · Medicity";
    const robots = document.createElement("meta");
    robots.name = "robots";
    robots.content = "noindex";
    document.head.appendChild(robots);
    return () => robots.remove();
  }, []);

  // One smooth-scroll engine (Lenis), driven by GSAP's ticker, feeding ScrollTrigger.
  useEffect(() => {
    if (!can3D || !journey.current) return;
    gsap.registerPlugin(ScrollTrigger);
    const lenis = new Lenis({ lerp: 0.1 });
    lenis.on("scroll", ScrollTrigger.update);
    const tick = (time: number) => lenis.raf(time * 1000);
    gsap.ticker.add(tick);
    gsap.ticker.lagSmoothing(0);
    const trigger = ScrollTrigger.create({
      trigger: journey.current,
      start: "top top",
      end: "bottom bottom",
      onUpdate: (self) => {
        progress.current = self.progress;
      },
    });
    return () => {
      trigger.kill();
      gsap.ticker.remove(tick);
      lenis.destroy();
    };
  }, [can3D]);

  const flatBody = <BodyMapCanvas view={view} onViewChange={setView} selected={region} onSelect={setRegion} />;

  return (
    <div className="lab">
      <header className="lab__bar">
        <Link to="/" className="lab__brand">
          <span className="lab__mark" aria-hidden="true" />
          Medicity
        </Link>
        <span className="lab__tag">3D preview</span>
        <Link to="/login" className="lab__cta">
          Sign in
        </Link>
      </header>

      <section className="j3" ref={journey} aria-labelledby="lab-title">
        <div className="j3__stage" aria-hidden={!can3D}>
          {can3D ? (
            <Suspense fallback={null}>
              <JourneyCanvas progress={progress} />
            </Suspense>
          ) : (
            <div className="j3__flat" />
          )}
        </div>
        <div className="j3__copy">
          <div className="j3__chapter j3__chapter--first">
            <h1 id="lab-title" className="lab__title">
              From prescription
              <br /> to medicines in hand, near you.
            </h1>
            <p className="lab__lede">Scroll to follow one prescription along the line.</p>
          </div>
          {CHAPTERS.map((c, i) => (
            <div key={c.name} className={`j3__chapter ${i % 2 ? "j3__chapter--right" : ""}`}>
              <p className="j3__no">{i + 1}</p>
              <h2>{c.name}</h2>
              <p className="j3__title">{c.title}</p>
              <p className="j3__text">{c.text}</p>
            </div>
          ))}
        </div>
      </section>

      <section className="lab__section" aria-labelledby="body-title">
        <div className="lab__split">
          <div>
            <h2 id="body-title" className="lab__h2">
              Not sure who to consult?
            </h2>
            <p className="lab__lede">
              Turn the body and tap where it hurts. {region ? `You chose ${BODY_TAXONOMY[region]?.label.toLowerCase()}.` : ""}
            </p>
          </div>
          <div className="lab__body">
            {can3D ? (
              <Suspense fallback={<div className="lab__wait">Loading…</div>}>
                <BodyMap3D view={view} onViewChange={setView} selected={region} onSelect={setRegion} fallback={flatBody} />
              </Suspense>
            ) : (
              flatBody
            )}
          </div>
        </div>
      </section>

      <section className="lab__section" aria-labelledby="map-title">
        <div className="lab__split lab__split--map">
          <div>
            <h2 id="map-title" className="lab__h2">
              Every chemist within 3 km hears it at once.
            </h2>
            <p className="lab__lede">
              Five verified chemists asked for 14 omeprazole capsules. Three have answered: Green Cross has all 14, Lakshmi
              has the same medicine for less, and Nightingale has 10 of 14.
            </p>
            <p className="lab__note">An example. Sign in to see the chemists around you.</p>
          </div>
          <div className="lab__map">
            {can3D ? (
              <Suspense fallback={<div className="lab__wait">Loading…</div>}>
                <CityMap3D fallback={<div className="lab__wait">The map could not be drawn.</div>} />
              </Suspense>
            ) : (
              <p className="lab__wait">The 3D map is off on this device. The flat map is on the home page.</p>
            )}
          </div>
        </div>
      </section>
    </div>
  );
}
