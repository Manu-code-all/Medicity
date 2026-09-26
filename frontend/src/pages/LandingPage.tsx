import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { doctors } from "../api/endpoints";
import { useAuth } from "../auth/context";
import { initials } from "../lib/format";

const API_DOCS_URL = `${import.meta.env.VITE_API_BASE_URL ?? ""}/swagger-ui/index.html`;

export function LandingPage() {
  const { session } = useAuth();
  const isPatient = session?.role === "PATIENT";

  return (
    <div className="landing">
      <section className="hero">
        <div className="hero__inner">
          <div className="hero__copy">
            <p className="eyebrow">Multi-speciality care · Online booking</p>
            <h1>
              Healthcare that <span className="accent">remembers you</span>.
            </h1>
            <p className="hero__lede">
              Book a specialist in under a minute. Every visit, prescription and follow-up
              afterwards lives in one private portal — so you never have to dig for an old
              slip of paper again.
            </p>
            <div className="hero__actions">
              <Link className="button button--lg" to="/doctors">
                Find a doctor
              </Link>
              {isPatient ? (
                <Link className="button button--lg button--ghost" to="/portal">
                  Open my portal
                </Link>
              ) : (
                <Link className="button button--lg button--ghost" to="/login">
                  Patient sign in
                </Link>
              )}
            </div>
            {!session && (
              <p className="hero__demo muted">
                Trying it out? Sign in as <code>patient@medicity.demo</code> /{" "}
                <code>demo-password-2026</code>
              </p>
            )}
          </div>

          <PortalPreview />
        </div>
      </section>

      <section className="trust" aria-label="Why Medicity">
        <div className="trust__inner">
          <Feature icon={<IconCalendar />} title="Live availability">
            See real open times and book instantly — no calls, no waiting on hold.
          </Feature>
          <Feature icon={<IconLock />} title="Your slot is yours">
            Once you book a time, no one else can take it, even if they click at the same moment.
          </Feature>
          <Feature icon={<IconFile />} title="Prescriptions kept current">
            When a doctor revises a prescription, the old one is replaced — never two conflicting sets.
          </Feature>
          <Feature icon={<IconShield />} title="Private by design">
            Your records are visible only to you and the doctors who treat you.
          </Feature>
        </div>
      </section>

      <FeaturedDoctors />

      <section className="section">
        <div className="section__inner">
          <h2 className="section__title">How it works</h2>
          <ol className="steps">
            <li className="step">
              <span className="step__num">1</span>
              <h3>Choose a specialist</h3>
              <p className="muted">Filter by speciality or search by name. Fees and experience up front.</p>
            </li>
            <li className="step">
              <span className="step__num">2</span>
              <h3>Pick a time</h3>
              <p className="muted">Open slots for the next two weeks, confirmed the moment you book.</p>
            </li>
            <li className="step">
              <span className="step__num">3</span>
              <h3>Keep everything in one place</h3>
              <p className="muted">Visits, prescriptions and your profile — all in your patient portal.</p>
            </li>
          </ol>
        </div>
      </section>

      <section className="section section--tinted">
        <div className="section__inner split">
          <div>
            <h2 className="section__title">Your health history, in one place</h2>
            <p className="muted">
              The patient portal is where you land after signing in. It is built around the
              questions patients actually ask.
            </p>
          </div>
          <ul className="checklist">
            <li><strong>What's next?</strong> Your upcoming visit, front and centre, with a countdown.</li>
            <li><strong>What happened last time?</strong> A full visit history, including cancellations and missed visits.</li>
            <li><strong>What should I be taking?</strong> Every current prescription with dose, frequency and duration.</li>
            <li><strong>Can I change plans?</strong> Cancel an upcoming visit in one tap; the slot is freed for someone else.</li>
          </ul>
        </div>
      </section>

      <section className="cta">
        <div className="cta__inner">
          <h2>{isPatient ? "Welcome back." : "Create your account — it takes a minute."}</h2>
          <Link className="button button--lg button--light" to={isPatient ? "/portal" : "/register"}>
            {isPatient ? "Go to my portal" : "Get started"}
          </Link>
        </div>
      </section>

      <footer className="footer">
        <div className="footer__inner">
          <span>© {new Date().getFullYear()} Medicity</span>
          <nav>
            <Link to="/doctors">Find a doctor</Link>
            <a href={API_DOCS_URL} target="_blank" rel="noreferrer">
              API docs
            </a>
            <a href="https://github.com/Manu-code-all/Medicity" target="_blank" rel="noreferrer">
              Source
            </a>
          </nav>
        </div>
      </footer>
    </div>
  );
}

function FeaturedDoctors() {
  const query = useQuery({
    queryKey: ["doctors", "", ""],
    queryFn: () => doctors.search(),
    staleTime: 5 * 60_000,
  });

  const featured = query.data?.content.slice(0, 3) ?? [];

  // The section is a bonus, not the page: if the API is slow or down, the
  // landing page still renders fully without it.
  if (featured.length === 0) return null;

  return (
    <section className="section">
      <div className="section__inner">
        <div className="section__head">
          <h2 className="section__title">Meet our specialists</h2>
          <Link to="/doctors">See all doctors →</Link>
        </div>
        <ul className="doctor-grid">
          {featured.map((doctor) => (
            <li key={doctor.id} className="card doctor-card">
              <div className="avatar" aria-hidden="true">
                {initials(doctor.fullName)}
              </div>
              <h3>{doctor.fullName}</h3>
              <p className="muted">
                {doctor.specialization} · {doctor.yearsExperience} yrs experience
              </p>
              {doctor.bio && <p className="doctor-card__bio">{doctor.bio}</p>}
              <div className="doctor-card__foot">
                <span className="fee">₹{doctor.consultationFee}</span>
                <Link className="button" to={`/doctors/${doctor.id}/book`}>
                  Book
                </Link>
              </div>
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}

/** A static illustration of the portal, so visitors see what they get. */
function PortalPreview() {
  return (
    <div className="preview" aria-hidden="true">
      <div className="preview__bar">
        <span />
        <span />
        <span />
      </div>
      <div className="preview__body">
        <p className="preview__label">Next visit · in 3 days</p>
        <div className="preview__visit">
          <div className="date-tile">
            <span className="date-tile__day">14</span>
            <span className="date-tile__month">OCT</span>
          </div>
          <div>
            <strong>Dr. Suresh Iyer</strong>
            <p className="muted">Neurology · 11:00</p>
          </div>
        </div>
        <div className="preview__stats">
          <div><strong>4</strong><span>Visits</span></div>
          <div><strong>3</strong><span>Prescriptions</span></div>
          <div><strong>1</strong><span>Upcoming</span></div>
        </div>
        <p className="preview__label">Current prescription</p>
        <div className="preview__rx">
          <span>Paracetamol 500mg</span>
          <span className="muted">As needed</span>
        </div>
        <div className="preview__rx">
          <span>Omeprazole 20mg</span>
          <span className="muted">Before breakfast</span>
        </div>
      </div>
    </div>
  );
}

function Feature({ icon, title, children }: { icon: React.ReactNode; title: string; children: React.ReactNode }) {
  return (
    <div className="feature">
      <span className="feature__icon">{icon}</span>
      <h3>{title}</h3>
      <p className="muted">{children}</p>
    </div>
  );
}

const svgProps = {
  width: 22,
  height: 22,
  viewBox: "0 0 24 24",
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 2,
  strokeLinecap: "round" as const,
  strokeLinejoin: "round" as const,
  "aria-hidden": true,
};

function IconCalendar() {
  return (
    <svg {...svgProps}>
      <rect x="3" y="4" width="18" height="18" rx="2" />
      <path d="M16 2v4M8 2v4M3 10h18" />
    </svg>
  );
}

function IconLock() {
  return (
    <svg {...svgProps}>
      <rect x="4" y="11" width="16" height="10" rx="2" />
      <path d="M8 11V7a4 4 0 0 1 8 0v4" />
    </svg>
  );
}

function IconFile() {
  return (
    <svg {...svgProps}>
      <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
      <path d="M14 2v6h6M9 13h6M9 17h6" />
    </svg>
  );
}

function IconShield() {
  return (
    <svg {...svgProps}>
      <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
    </svg>
  );
}
