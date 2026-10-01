import { useEffect } from "react";
import { Link } from "react-router-dom";
import {
  ArrowRight,
  Baby,
  Bone,
  CalendarCheck,
  Clock,
  FirstAidKit,
  HandPalm,
  Heartbeat,
  Package,
  Prescription,
  Storefront,
  Tooth,
} from "@phosphor-icons/react";
import { BodyGuide } from "../components/bodymap/BodyGuide";
import { IslandNav } from "../components/IslandNav";
import { LineMap } from "../components/LineMap";
import { DoctorOmnibar } from "../components/search/DoctorOmnibar";
import "../landing.css";
import "./designSample.css";

const SPECIALITIES = [
  { name: "General Medicine", label: "General physician", Icon: FirstAidKit },
  { name: "Dermatology", label: "Skin", Icon: HandPalm },
  { name: "Orthopaedics", label: "Bones and joints", Icon: Bone },
  { name: "Paediatrics", label: "Children", Icon: Baby },
  { name: "Dentistry", label: "Teeth", Icon: Tooth },
  { name: "Cardiology", label: "Heart", Icon: Heartbeat },
];

const STATIONS = [
  { Icon: CalendarCheck, name: "Book", hindi: "बुकिंग", text: "A time that is yours the moment you take it." },
  { Icon: Clock, name: "Visit", hindi: "मुलाक़ात", text: "Counted down in your portal. Free to cancel." },
  { Icon: Prescription, name: "Prescription", hindi: "पर्चा", text: "Written by your doctor and kept current." },
  { Icon: Storefront, name: "Chemists nearby", hindi: "पास के केमिस्ट", text: "Ask them all at once. Compare the answers." },
  { Icon: Package, name: "Pick up", hindi: "दवा लें", text: "Kept aside and handed over against your code." },
];

/**
 * A sample of a bolder 2D landing page, in the same Enamel Signage world: one
 * focal point in the first screen, colour in blocks, big type, and the
 * line drawn large. Not linked from anywhere and not indexed.
 */
export function DesignSample() {
  useEffect(() => {
    document.title = "Design sample · Medicity";
    const robots = document.createElement("meta");
    robots.name = "robots";
    robots.content = "noindex";
    document.head.appendChild(robots);
    return () => robots.remove();
  }, []);

  return (
    <div className="lm ds">
      <a className="lm-skip" href="#main">
        Skip to content
      </a>
      <IslandNav />

      <main id="main">
        {/* 1. The first screen: one promise, one action, one picture of the product. */}
        <section className="ds-hero" aria-labelledby="ds-title">
          <div className="ds-hero__copy">
            <h1 id="ds-title" className="ds-title">
              Find your doctor.
              <br /> Then your medicines
              <br /> <span className="ds-title__end">nearby.</span>
            </h1>
            <p className="ds-lede">
              Book a specialist, keep every prescription in one place, and ask every verified chemist within 3 km who has
              your medicines and at what price.
            </p>

            <div className="ds-search">
              <DoctorOmnibar />
            </div>
            <ul className="ds-chips" aria-label="Popular specialities">
              {SPECIALITIES.map(({ name, label, Icon }) => (
                <li key={name}>
                  <Link to={`/doctors?${new URLSearchParams({ specialty: name })}`} className="ds-chip">
                    <Icon size={18} weight="bold" aria-hidden="true" />
                    {label}
                  </Link>
                </li>
              ))}
            </ul>
            <a href="#guide" className="ds-guide-link">
              Not sure who to see? Tap where it hurts
              <ArrowRight size={18} weight="bold" aria-hidden="true" />
            </a>
          </div>

          {/* The product in three real pieces, on the line that connects them. */}
          <div className="ds-stack" aria-hidden="true">
            <div className="ds-stack__line">
              <span className="ds-stack__stop ds-stack__stop--a" />
              <span className="ds-stack__stop ds-stack__stop--b" />
              <span className="ds-stack__stop ds-stack__stop--c" />
            </div>

            <div className="ds-card ds-card--ticket">
              <div className="ds-ticket__stub">
                <strong className="ds-num">2</strong>
                <span className="ds-num">OCT</span>
                <span className="ds-num ds-ticket__time">10:30</span>
              </div>
              <div className="ds-ticket__body">
                <p className="ds-card__kicker">Tomorrow</p>
                <p className="ds-card__big">Dr. Anjali Rao</p>
                <p className="ds-card__sub">Cardiology · Indiranagar</p>
              </div>
            </div>

            <div className="ds-card ds-card--answer">
              <div className="ds-answer__row">
                <div>
                  <p className="ds-card__big">Lakshmi Medical Stores</p>
                  <p className="ds-card__sub">Omez, the same medicine</p>
                </div>
                <p className="ds-answer__price ds-num">₹58.80</p>
              </div>
              <div className="ds-answer__foot">
                <span className="ds-num">990 m away</span>
                <span className="ds-pill">Cheapest</span>
              </div>
            </div>

            <div className="ds-card ds-card--code">
              <p className="ds-card__sub">Show this at the counter</p>
              <div className="ds-code">
                {"482913".split("").map((d, i) => (
                  <span key={i} className="ds-num">
                    {d}
                  </span>
                ))}
              </div>
            </div>
          </div>
        </section>

        {/* 2. The line, drawn large: the page's signature. */}
        <section className="ds-line" aria-labelledby="ds-line-title">
          <h2 id="ds-line-title" className="ds-line__title">
            One line from the clinic
            <br /> to the chemist's counter.
          </h2>
          <ol className="ds-stations">
            {STATIONS.map(({ Icon, name, hindi, text }) => (
              <li key={name} className="ds-station">
                <span className="ds-station__roundel" aria-hidden="true" />
                <Icon className="ds-station__icon" size={28} weight="bold" aria-hidden="true" />
                <h3>{name}</h3>
                <p className="ds-station__hindi" lang="hi">
                  {hindi}
                </p>
                <p className="ds-station__text">{text}</p>
              </li>
            ))}
          </ol>
        </section>

        {/* 3. The idea in one picture: a question out, answers back. */}
        <section className="ds-ask" aria-labelledby="ds-ask-title">
          <div className="ds-ask__copy">
            <h2 id="ds-ask-title" className="ds-h2">
              Every chemist within 3 km hears it at once.
            </h2>
            <p className="ds-lede">
              One tap sends the prescription to every verified chemist around you. See who has it, what it costs and how far
              it is, then keep it aside and collect it with a code.
            </p>
            <Link to="/doctors" className="ds-btn">
              Book a visit
              <ArrowRight size={20} weight="bold" aria-hidden="true" />
            </Link>
          </div>
          <div className="ds-ask__map">
            <LineMap />
          </div>
        </section>

        {/* 4. The other door. */}
        <section className="ds-guide" id="guide" aria-labelledby="ds-guide-title">
          <div className="ds-guide__copy">
            <h2 id="ds-guide-title" className="ds-h2">
              Not sure who to see?
            </h2>
            <p className="ds-lede">Tap where it hurts and say what it feels like. We suggest the kind of specialist to book.</p>
          </div>
          <div className="ds-guide__panel">
            <BodyGuide />
          </div>
        </section>

        {/* 5. Close on one colour and one action. */}
        <section className="ds-close" aria-labelledby="ds-close-title">
          <h2 id="ds-close-title" className="ds-close__title">
            Your next prescription
            <br /> can find its own chemist.
          </h2>
          <div className="ds-close__actions">
            <Link to="/doctors" className="ds-btn ds-btn--light">
              Book a visit
              <ArrowRight size={20} weight="bold" aria-hidden="true" />
            </Link>
            <Link to="/login" className="ds-btn ds-btn--ghost">
              Sign in
            </Link>
          </div>
          <p className="ds-close__note">Free for doctors and chemists. No stock list to keep. Every chemist's licence is checked by a person.</p>
        </section>
      </main>
    </div>
  );
}
