import { useEffect, useRef, useState, type ReactNode } from "react";
import { Link, useLocation } from "react-router-dom";
import { ArrowRight, CalendarCheck, Clock, Package, Prescription, Storefront } from "@phosphor-icons/react";
import { BodyGuide } from "../components/bodymap/BodyGuide";
import { IslandNav } from "../components/IslandNav";
import { LineMap } from "../components/LineMap";
import { DoctorOmnibar } from "../components/search/DoctorOmnibar";
import { SpecialtyQuickGrid } from "../components/search/SpecialtyQuickGrid";
import { DEMO_PASSWORD } from "../lib/demo";
import { useInView } from "../lib/reveal";
import "../landing.css";

const API_DOCS_URL = `${import.meta.env.VITE_API_BASE_URL ?? ""}/swagger-ui/index.html`;

export function LandingPage() {
  const location = useLocation();

  // React Router does not scroll to #fragments by itself.
  useEffect(() => {
    if (location.hash) document.getElementById(location.hash.slice(1))?.scrollIntoView({ behavior: "smooth" });
  }, [location.hash]);

  return (
    <div className="lm">
      <a className="lm-skip" href="#main">
        Skip to content
      </a>
      <IslandNav />

      <main id="main">
        <section className="lm-doors" aria-labelledby="doors-title">
          {/* The capsule above shows this line; the page keeps it as its heading. */}
          <h1 id="doors-title" className="lm-sr">
            Find the right doctor, then your medicines nearby.
          </h1>
          <div className="lm-doors__grid">
            <section className="lm-door" aria-labelledby="door-guide">
              <h2 id="door-guide" className="lm-door__title">
                Not sure who to consult?
              </h2>
              <p className="lm-door__lede">
                Tap where it hurts and say what it feels like. We suggest the kind of specialist to see.
              </p>
              <BodyGuide />
            </section>
            <section className="lm-door" aria-labelledby="door-search">
              <h2 id="door-search" className="lm-door__title">
                Know who you are looking for?
              </h2>
              <p className="lm-door__lede">Search by doctor or speciality, or pick one of the most asked for.</p>
              <DoctorOmnibar />
              <SpecialtyQuickGrid />
              <Link to="/doctors" className="lm-text-link">
                See every doctor
                <ArrowRight size={16} weight="bold" aria-hidden="true" />
              </Link>
            </section>
          </div>
        </section>

        <section className="lm-hero" aria-labelledby="hero-title">
          <div className="lm-hero__copy">
            <h2 id="hero-title" className="lm-sr">
              From prescription to medicines in hand, near you.
            </h2>
            <p className="lm-hero__lede">
              Book a specialist, keep every prescription in one place, then ask every verified chemist within 3&nbsp;km who
              has your medicines and at what price. Pick up with a code.
            </p>
            <div className="lm-hero__actions">
              <Link to="/doctors" className="lm-button">
                Book a visit
              </Link>
            </div>
            <p className="lm-hero__proof">Free for doctors and chemists. No stock list to keep, no fee per prescription.</p>
          </div>
          <LineMap />
        </section>

        <Journey />

        <TaglineReveal
          lines={["Your prescription already knows the way.", "Every verified chemist within 3 km hears it at once."]}
        />

        <Faq />

        <section className="lm-close" aria-labelledby="close-title">
          <h2 id="close-title" className="lm-close__title">
            Your next prescription
            <br /> can find its own chemist.
          </h2>
          <div className="lm-hero__actions">
            <Link to="/doctors" className="lm-button">
              Book a visit
            </Link>
          </div>
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

/* ---------- The line: five stations, each a real piece of the app ---------- */

interface StationProps {
  icon: ReactNode;
  name: string;
  hindi: string;
  title: string;
  children: ReactNode;
  panel: ReactNode;
  last?: boolean;
}

function Station({ icon, name, hindi, title, children, panel, last }: StationProps) {
  const [ref, seen] = useInView<HTMLLIElement>("0px 0px -35% 0px");
  return (
    <li ref={ref} className="lm-station" data-reached={seen} data-last={last || undefined}>
      <span className="lm-station__track" aria-hidden="true">
        <span className="lm-station__fill" />
      </span>
      <span className="lm-roundel lm-roundel--station" aria-hidden="true" />
      <div className="lm-station__sign">
        <h3 className="lm-station__name">
          <span className="lm-station__icon" aria-hidden="true">{icon}</span>
          {name}
        </h3>
        <p className="lm-station__hindi" lang="hi">{hindi}</p>
        <p className="lm-station__title">{title}</p>
        <p className="lm-station__text">{children}</p>
      </div>
      <div className="lm-station__panel" aria-hidden="true">{panel}</div>
    </li>
  );
}

function Journey() {
  return (
    <section className="lm-journey" id="line" aria-labelledby="line-title">
      <h2 id="line-title" className="lm-h2">
        One line from the clinic
        <br /> to the chemist's counter
      </h2>
      <ol className="lm-stations">
        <Station
          icon={<CalendarCheck size={28} weight="bold" />}
          name="Book"
          hindi="बुकिंग"
          title="A slot that is yours the moment you take it"
          panel={<SlotPanel />}
        >
          Real open times for the next two weeks. If two people press the same slot in the same second, one gets
          it and the other is told at once.
        </Station>
        <Station
          icon={<Clock size={28} weight="bold" />}
          name="Visit"
          hindi="मुलाक़ात"
          title="Counted down in your portal, free to cancel"
          panel={<VisitPanel />}
        >
          Your next visit waits at the top of your portal. Cancelling frees the slot for someone else straight away,
          and your parents' and children's visits sit beside yours.
        </Station>
        <Station
          icon={<Prescription size={28} weight="bold" />}
          name="Prescription"
          hindi="पर्चा"
          title="Written by your doctor, kept current"
          panel={<RxPanel />}
        >
          When a doctor corrects a prescription the old one is replaced, never two versions. How to take each
          medicine, in Hindi, Tamil, Kannada, Telugu or Bengali.
        </Station>
        <Station
          icon={<Storefront size={28} weight="bold" />}
          name="Chemists nearby"
          hindi="पास के केमिस्ट"
          title="Ask them all at once, compare the answers"
          panel={<ComparePanel />}
        >
          One tap sends the prescription to every verified chemist within 1, 3 or 5 km. See who has it, the price,
          the distance, and a cheaper brand your doctor allowed.
        </Station>
        <Station
          icon={<Package size={28} weight="bold" />}
          name="Pick up"
          hindi="दवा लें"
          title="Kept aside, handed over against your code"
          panel={<CodePanel />}
          last
        >
          The store holds it for 2 to 4 hours. Show your six digit code and pay at the counter. Before you run
          out, Medicity reminds you to ask again.
        </Station>
      </ol>
    </section>
  );
}

function SlotPanel() {
  return (
    <div className="lm-panel">
      <p className="lm-panel__head">
        <strong>Dr. Suresh Iyer</strong> · Neurology
      </p>
      <p className="lm-panel__sub">Wednesday, 14 October</p>
      <div className="lm-slots">
        <span>10:00</span>
        <span>10:30</span>
        <span className="is-picked">11:00</span>
        <span className="is-gone">11:30</span>
        <span>12:00</span>
        <span>12:30</span>
      </div>
    </div>
  );
}

function VisitPanel() {
  return (
    <div className="lm-panel">
      <p className="lm-panel__sub">Tomorrow</p>
      <div className="lm-visit">
        <span className="lm-visit__time lm-num">11:00</span>
        <div>
          <p className="lm-panel__head"><strong>Dr. Suresh Iyer</strong></p>
          <p className="lm-panel__sub">Neurology · for Lalitha Nair, mother</p>
        </div>
      </div>
      <span className="lm-panel__action">Cancel visit</span>
    </div>
  );
}

function RxPanel() {
  return (
    <div className="lm-panel">
      <p className="lm-panel__head">
        <strong>Omeprazole 20mg</strong> · capsule
      </p>
      <p className="lm-panel__sub">1 capsule before breakfast · 14 days · by Dr. Anjali Rao</p>
      <p className="lm-panel__hindi" lang="hi">नाश्ते से पहले एक कैप्सूल, 14 दिन तक</p>
      <span className="lm-chip">Cheaper brand allowed</span>
    </div>
  );
}

function ComparePanel() {
  const rows = [
    { store: "Lakshmi Medical Stores", what: "Omez, same medicine", price: "₹58.80", far: "990 m", best: true },
    { store: "Green Cross Pharmacy", what: "All 14", price: "₹77.00", far: "740 m" },
    { store: "Nightingale 24x7", what: "10 of 14", price: "₹58.00", far: "1.5 km" },
  ];
  return (
    <div className="lm-panel lm-panel--flush">
      <ul className="lm-compare">
        {rows.map((r) => (
          <li key={r.store}>
            <span>
              <strong>{r.store}</strong>
              <span className="lm-panel__sub">{r.what}</span>
            </span>
            <span className="lm-compare__side">
              <span className="lm-num lm-compare__price">{r.price}</span>
              <span className="lm-panel__sub lm-num">{r.far}</span>
              {r.best && <span className="lm-chip lm-chip--solid">Cheapest</span>}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** The pick-up code drawn with ghost cells: every unlit cell still shows its frame. */
function CodePanel() {
  return (
    <div className="lm-panel">
      <p className="lm-panel__sub">Show this at Lakshmi Medical Stores</p>
      <div className="lm-code" aria-label="Pick up code 482913">
        {"482913".split("").map((d, i) => (
          <SegmentDigit key={i} digit={Number(d)} />
        ))}
      </div>
      <p className="lm-panel__sub">Kept aside until 3:40 pm · pay at the counter</p>
    </div>
  );
}

/** Which of the seven segments (a to g, clockwise from the top, g in the middle) each digit lights. */
const SEGMENTS = ["abcdef", "bc", "abdeg", "abcdg", "bcfg", "acdfg", "acdefg", "abc", "abcdefg", "abcdfg"];
const SEGMENT_SHAPES: Record<string, string> = {
  a: "M6 2h12l-2 4H8z",
  b: "M20 4l2 2v12l-2 2-2-2V8z",
  c: "M20 22l2 2v12l-2 2-2-2V26z",
  d: "M6 40h12l-2-4H8z",
  e: "M4 22l2 2v10l-2 4-2-2V24z",
  f: "M4 4l2 4v10l-2 2-2-2V6z",
  g: "M6 21l2-2h8l2 2-2 2H8z",
};

/** One cell of the code: every segment is drawn, and only the digit's segments are lit. */
function SegmentDigit({ digit }: { digit: number }) {
  const lit = SEGMENTS[digit] ?? "";
  return (
    <span className="lm-code__cell">
      <svg viewBox="0 0 24 42" aria-hidden="true">
        {Object.entries(SEGMENT_SHAPES).map(([id, d]) => (
          <path key={id} d={d} className={lit.includes(id) ? "is-lit" : undefined} />
        ))}
      </svg>
    </span>
  );
}

/* ---------- The tagline, lit word by word as it crosses the middle of the screen ---------- */

function TaglineReveal({ lines }: { lines: string[] }) {
  const ref = useRef<HTMLParagraphElement>(null);
  // Words below `count` are lit; `from` is where the latest batch started, so
  // words crossing the line together still light one after another.
  const [lit, setLit] = useState(() => ({ count: typeof IntersectionObserver === "undefined" ? Infinity : 0, from: 0 }));

  useEffect(() => {
    const el = ref.current;
    if (!el || typeof IntersectionObserver === "undefined") return;
    const words = Array.from(el.querySelectorAll<HTMLElement>("[data-word]"));
    const observer = new IntersectionObserver(
      (entries) => {
        const reached = Math.max(
          0,
          ...entries.filter((e) => e.isIntersecting).map((e) => Number((e.target as HTMLElement).dataset.word) + 1),
        );
        setLit((prev) => (reached > prev.count ? { count: reached, from: prev.count } : prev));
      },
      // The trigger line sits just below the middle of the viewport.
      { rootMargin: "0px 0px -45% 0px", threshold: 1 },
    );
    words.forEach((w) => observer.observe(w));
    return () => observer.disconnect();
  }, []);

  let index = 0;
  return (
    <section className="lm-tagline" aria-label="What Medicity does">
      <p ref={ref} className="lm-tagline__text">
        {lines.map((line, li) => (
          <span key={li} className="lm-tagline__line">
            {line.split(" ").map((word) => {
              const i = index++;
              const on = i < lit.count;
              return (
                <span
                  key={i}
                  data-word={i}
                  className={on ? "is-lit" : undefined}
                  style={on && i >= lit.from ? { transitionDelay: `${(i - lit.from) * 80}ms` } : undefined}
                >
                  {word}{" "}
                </span>
              );
            })}
          </span>
        ))}
      </p>
    </section>
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
