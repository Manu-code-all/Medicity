import { useEffect, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { ArrowLeft } from "@phosphor-icons/react";
import { ApiError } from "../api/client";
import { auth as authApi } from "../api/endpoints";
import type { CodeSent } from "../api/types";
import { homeFor, useAuth, type Session } from "../auth/context";
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

  // Mobile first, as Indian health platforms do; email stays one tap away.
  const [method, setMethod] = useState<"mobile" | "email">("mobile");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<"form" | "demo" | null>(null);

  // A protected page that sent the user here wins; otherwise each role goes home.
  const returnTo = (location.state as { from?: string } | null)?.from;
  const copy = COPY[role];
  const demo = DEMO_ACCOUNTS[role];
  const why = doctorsWaiting(returnTo);

  function signedIn(session: Session) {
    navigate(returnTo ?? homeFor(session.role), { replace: true });
  }

  async function signIn(address: string, secret: string, how: "form" | "demo") {
    setError(null);
    setBusy(how);
    try {
      signedIn(await login(address, secret));
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
          {why && <p className="lm-auth__why">{why}</p>}

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
            <span>or sign in with</span>
          </p>

          <div className="lm-method" role="tablist" aria-label="Sign in with">
            {(["mobile", "email"] as const).map((m) => (
              <button
                key={m}
                type="button"
                role="tab"
                aria-selected={method === m}
                className="lm-method__tab"
                onClick={() => {
                  setError(null);
                  setMethod(m);
                }}
              >
                {m === "mobile" ? "Mobile number" : "Email"}
              </button>
            ))}
          </div>

          {method === "mobile" ? (
            <MobileSignIn demoPhone={demo.phone} onSignedIn={signedIn} useEmail={() => setMethod("email")} />
          ) : (
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
          )}

          <p className="lm-auth__foot">
            {role === "patient" && (
              <>
                New here? <Link to="/register" state={location.state}>Create an account</Link>
              </>
            )}
            {role === "doctor" && (
              <>
                New to Medicity? <Link to="/register/doctor">Join as a doctor</Link>
              </>
            )}
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

const RESEND_AFTER_SECONDS = 30;

/**
 * Mobile number, then a six digit code. Until an SMS provider is configured,
 * only the demo accounts can use this: their code is shown on screen.
 */
function MobileSignIn({
  demoPhone,
  onSignedIn,
  useEmail,
}: {
  demoPhone: string;
  onSignedIn: (session: Session) => void;
  useEmail: () => void;
}) {
  const { loginWithCode } = useAuth();
  const [phone, setPhone] = useState("");
  const [sent, setSent] = useState<CodeSent | null>(null);
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [wait, setWait] = useState(0);

  useEffect(() => {
    if (wait <= 0) return;
    const t = window.setTimeout(() => setWait((w) => w - 1), 1000);
    return () => window.clearTimeout(t);
  }, [wait]);

  async function send() {
    setError(null);
    setBusy(true);
    try {
      const reply = await authApi.sendCode(phone);
      setSent(reply);
      setCode("");
      setWait(reply.delivery === "UNAVAILABLE" ? 0 : RESEND_AFTER_SECONDS);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not send the code. Check your connection.");
    } finally {
      setBusy(false);
    }
  }

  async function verify() {
    setError(null);
    setBusy(true);
    try {
      onSignedIn(await loginWithCode(phone, code));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not sign in. Check your connection.");
      setCode("");
    } finally {
      setBusy(false);
    }
  }

  if (sent?.delivery === "UNAVAILABLE") {
    return (
      <div className="lm-form">
        <p className="lm-note" role="status">
          Codes by SMS are not switched on yet, so only the demo numbers can use them. Sign in with your email
          instead, or try the demo number <span className="lm-num">{demoPhone}</span>.
        </p>
        <button type="button" className="lm-button lm-button--block" onClick={useEmail}>
          Use email instead
        </button>
        <button type="button" className="lm-link-button" onClick={() => setSent(null)}>
          Change number
        </button>
      </div>
    );
  }

  if (!sent) {
    return (
      <form
        className="lm-form"
        onSubmit={(e) => {
          e.preventDefault();
          void send();
        }}
      >
        {error && (
          <p className="lm-error" role="alert">
            {error}
          </p>
        )}
        <label htmlFor="phone">Mobile number</label>
        <div className="lm-phone">
          <span className="lm-phone__prefix" aria-hidden="true">
            +91
          </span>
          <input
            id="phone"
            type="tel"
            inputMode="numeric"
            autoComplete="tel-national"
            required
            placeholder="98765 43210"
            value={phone}
            onChange={(e) => setPhone(e.target.value.replace(/[^0-9 ]/g, ""))}
          />
        </div>
        <p className="lm-hint">
          Demo number: <span className="lm-num">{demoPhone}</span>
        </p>
        <button type="submit" className="lm-button lm-button--block" disabled={busy}>
          {busy ? "Sending…" : "Send code"}
        </button>
      </form>
    );
  }

  return (
    <form
      className="lm-form"
      onSubmit={(e) => {
        e.preventDefault();
        void verify();
      }}
    >
      {error && (
        <p className="lm-error" role="alert">
          {error}
        </p>
      )}
      <p className="lm-note" role="status">
        {sent.delivery === "DEMO" ? (
          <>
            Demo account, so no SMS: your code is <strong className="lm-num">{sent.demoCode}</strong>.{" "}
            <button type="button" className="lm-link-button" onClick={() => setCode(sent.demoCode ?? "")}>
              Fill it in
            </button>
          </>
        ) : (
          <>If {sent.sentTo} has an account, a code is on its way. It works for 5 minutes.</>
        )}
      </p>
      <label htmlFor="code">6 digit code</label>
      <input
        id="code"
        className="lm-num lm-code-input"
        inputMode="numeric"
        autoComplete="one-time-code"
        required
        pattern="[0-9]{6}"
        maxLength={6}
        value={code}
        onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
      />
      <button type="submit" className="lm-button lm-button--block" disabled={busy || code.length !== 6}>
        {busy ? "Signing in…" : "Verify and sign in"}
      </button>
      <div className="lm-form__row">
        <button type="button" className="lm-link-button" onClick={() => setSent(null)}>
          Change number
        </button>
        <button type="button" className="lm-link-button" disabled={wait > 0 || busy} onClick={() => void send()}>
          {wait > 0 ? `Send again in ${wait}s` : "Send again"}
        </button>
      </div>
    </form>
  );
}

/** Says why sign-in was asked for when a visitor was on their way to the doctors list. */
function doctorsWaiting(returnTo: string | undefined): string | null {
  if (!returnTo?.startsWith("/doctors")) return null;
  const params = new URLSearchParams(returnTo.split("?")[1] ?? "");
  const specialty = params.get("specialty");
  const name = params.get("q");
  if (specialty) return `Sign in to see the ${specialty} doctors available and book a time.`;
  if (name) return `Sign in to see doctors matching “${name}” and book a time.`;
  return "Sign in to see the doctors available and book a time.";
}
