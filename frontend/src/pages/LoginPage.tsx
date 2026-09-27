import { useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { ArrowLeft } from "@phosphor-icons/react";
import { ApiError } from "../api/client";
import { homeFor, useAuth } from "../auth/context";
import { DEMO_ACCOUNTS, DEMO_PASSWORD, LOGIN_PATH, type LineRole } from "../lib/demo";
import "../landing.css";

const ROLES: LineRole[] = ["patient", "doctor", "chemist"];

const COPY: Record<LineRole, { tab: string; title: string; pitch: string; stops: string[] }> = {
  patient: {
    tab: "Patient",
    title: "Sign in as a patient",
    pitch: "Your visits, prescriptions and medicines, and your family's, on one line.",
    stops: ["Book", "Visit", "Prescription", "Chemists nearby", "Pick up"],
  },
  doctor: {
    tab: "Doctor",
    title: "Sign in as a doctor",
    pitch: "Today's schedule, each patient's history, and prescriptions typed or photographed.",
    stops: ["Schedule", "Visit", "Prescription", "With the patient"],
  },
  chemist: {
    tab: "Chemist",
    title: "Sign in as a chemist",
    pitch: "Questions from patients nearby, answered in a tap. No stock list to keep.",
    stops: ["Question", "Answer", "Keep aside", "Hand over"],
  },
};

/** One sign-in page per role, in one shared frame; switching role slides the line across. */
export function LoginPage({ role = "patient" }: { role?: LineRole }) {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<"form" | "demo" | null>(null);

  // A protected page that sent the user here wins; otherwise each role goes home.
  const returnTo = (location.state as { from?: string } | null)?.from;
  const copy = COPY[role];
  const demo = DEMO_ACCOUNTS[role];

  async function signIn(address: string, secret: string, how: "form" | "demo") {
    setError(null);
    setBusy(how);
    try {
      const session = await login(address, secret);
      navigate(returnTo ?? homeFor(session.role), { replace: true });
    } catch (err) {
      // The server returns the same message whether the account is unknown or
      // the password is wrong; the UI must not elaborate on it either.
      setError(err instanceof ApiError ? err.message : "Could not sign in. Check your connection and try again.");
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="lm lm-auth" data-role={role}>
      <aside className="lm-auth__side">
        <Link to="/" className="lm-brand lm-brand--on-band">
          <span className="lm-brand__mark" aria-hidden="true" />
          Medicity
        </Link>
        <p className="lm-auth__pitch">{copy.pitch}</p>
        <ol className="lm-auth__line" aria-label={`The ${copy.tab.toLowerCase()} line`}>
          {copy.stops.map((stop) => (
            <li key={stop}>
              <span className="lm-roundel" aria-hidden="true" />
              {stop}
            </li>
          ))}
        </ol>
      </aside>

      <main className="lm-auth__main">
        <Link to="/" className="lm-text-link lm-auth__back">
          <ArrowLeft size={16} weight="bold" aria-hidden="true" />
          Home
        </Link>

        <div className="lm-auth__card">
          <nav className="lm-roles" aria-label="Sign in as" style={{ "--i": ROLES.indexOf(role) } as React.CSSProperties}>
            <span className="lm-roles__slider" aria-hidden="true" />
            {ROLES.map((r) => (
              <Link
                key={r}
                to={LOGIN_PATH[r]}
                state={location.state}
                replace
                className="lm-roles__tab"
                aria-current={r === role ? "page" : undefined}
              >
                {COPY[r].tab}
              </Link>
            ))}
          </nav>

          <h1 className="lm-auth__title">{copy.title}</h1>

          {error && (
            <p className="lm-error" role="alert">
              {error}
            </p>
          )}

          <button
            type="button"
            className="lm-demo"
            disabled={busy !== null}
            onClick={() => signIn(demo.email, DEMO_PASSWORD, "demo")}
          >
            <span className="lm-demo__title">{busy === "demo" ? "Signing in…" : `Try as ${demo.name}`}</span>
            <span className="lm-demo__sub">Demo account, {demo.who}</span>
          </button>

          <p className="lm-auth__or">
            <span>or with your email</span>
          </p>

          <form
            className="lm-form"
            onSubmit={(e) => {
              e.preventDefault();
              void signIn(email, password, "form");
            }}
          >
            <label htmlFor="email">Email</label>
            <input
              id="email"
              type="email"
              autoComplete="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />

            <label htmlFor="password">Password</label>
            <input
              id="password"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />

            <button type="submit" className="lm-button lm-button--block" disabled={busy !== null}>
              {busy === "form" ? "Signing in…" : "Sign in"}
            </button>
          </form>

          <p className="lm-auth__foot">
            {role === "patient" && (
              <>
                New here? <Link to="/register">Create an account</Link>
              </>
            )}
            {role === "doctor" && "Doctor accounts are set up by the clinic."}
            {role === "chemist" && (
              <>
                Not on Medicity yet? <Link to="/register/store">Register your store</Link>
              </>
            )}
          </p>
        </div>
      </main>
    </div>
  );
}
