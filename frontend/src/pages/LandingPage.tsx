import { useEffect } from "react";
import { Link, useLocation } from "react-router-dom";
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
import { DEMO_PASSWORD } from "../lib/demo";
import "../landing.css";
import "./landingBold.css";

const API_DOCS_URL = `${import.meta.env.VITE_API_BASE_URL ?? ""}/swagger-ui/index.html`;

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
 * The public first page. One promise and one action in the first screen (find
 * a doctor, then your medicines nearby, with the search box as the main
 * action), the line drawn large on an ink band, the question-and-answers map,
 * the body guide as the second door, the questions people ask first, and a
 * close on one colour and one action.
 */
export function LandingPage() {
  const location = useLocation();

  // React Router does not scroll to #fragments by itself.
  useEffect(() => {
    if (location.hash) document.getElementById(location.hash.slice(1))?.scrollIntoView({ behavior: "smooth" });
  }, [location.hash]);

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
                <p className="ds-card__sub">Cardiology · At the clinic</p>
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
              <div className="ds-code" aria-label="Pick up code 482913">
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

        <Faq />

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

      <footer className="lm-footer">
        <div className="lm-footer__inner">
          <Link to="/" className="lm-brand">
            <span className="lm-brand__mark" aria-hidden="true" />
            Medicity
          </Link>
          <nav aria-label="Footer">
            <ul>
              <li><Link to="/doctors">Find a doctor</Link></li>
              <li><Link to="/login">Patient sign in</Link></li>
              <li><Link to="/login/doctor">Doctor sign in</Link></li>
              <li><Link to="/login/chemist">Chemist sign in</Link></li>
              <li><Link to="/register/doctor">Join as a doctor</Link></li>
              <li><Link to="/register/store">Register your store</Link></li>
              <li><a href={API_DOCS_URL} target="_blank" rel="noreferrer">API docs</a></li>
              <li><a href="https://github.com/Manu-code-all/Medicity" target="_blank" rel="noreferrer">Source on GitHub</a></li>
            </ul>
          </nav>
          <p className="lm-footer__note">
            A public demo. Every account uses the password <code className="lm-num">{DEMO_PASSWORD}</code>, and all
            data resets every night.
          </p>
        </div>
      </footer>
    </div>
  );
}

/* ---------- Questions ---------- */

const FAQ: { q: string; a: string }[] = [
  {
    q: "Is Medicity a real service?",
    a: "It is a working product running as a public demo. The doctors, chemists and patients are demo accounts, and everything resets every night at 8:00 India time, so change anything you like.",
  },
  {
    q: "Who can see my prescription?",
    a: "You, the doctors who treat you, and the chemists you choose to ask. A chemist sees your prescription only after you ask it.",
  },
  {
    q: "How do you know a chemist is genuine?",
    a: "Every store registers with its drug licence number, and a person checks the licence before the store can receive any prescription.",
  },
  {
    q: "What if I do not collect in time?",
    a: "Each store holds medicines for 2 to 4 hours. If the time passes, the reservation lapses and your question opens again, so you can reserve somewhere else.",
  },
  {
    q: "Can I look after my parents' medicines?",
    a: "Yes. Add up to 8 family members, then book visits, ask chemists and keep prescriptions for them from your own account.",
  },
  {
    q: "What does it cost?",
    a: "Nothing for doctors and chemists. You pay the doctor's consultation fee and the chemist for your medicines, as you do today.",
  },
  {
    q: "Which languages does it speak?",
    a: "How to take each medicine is available in English, Hindi, Tamil, Kannada, Telugu and Bengali. The rest of the app is in English for now.",
  },
];

function Faq() {
  return (
    <section className="lm-faq" aria-labelledby="faq-title">
      <h2 id="faq-title" className="lm-h2">
        Questions people ask first
      </h2>
      <div className="lm-faq__list">
        {FAQ.map((item) => (
          <details key={item.q} className="lm-faq__item">
            <summary>{item.q}</summary>
            <p>{item.a}</p>
          </details>
        ))}
      </div>
    </section>
  );
}
