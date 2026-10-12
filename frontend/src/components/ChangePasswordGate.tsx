import { useState } from "react";
import { ApiError } from "../api/client";
import { useAuth } from "../auth/context";

/**
 * Over everything while the account still has the temporary password a clinic
 * gave it: nothing else works until the person chooses their own (the API
 * refuses every other request, so this is the only way forward). Signing out
 * is always possible.
 */
export function ChangePasswordGate() {
  const { session, changePassword, logout } = useAuth();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [again, setAgain] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (!session?.mustChangePassword) return null;

  const mismatch = again.length > 0 && again !== next;

  async function submit() {
    setError(null);
    setBusy(true);
    try {
      await changePassword(current, next);
      setCurrent("");
      setNext("");
      setAgain("");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not change the password. Check your connection.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="consent-gate" role="dialog" aria-modal="true" aria-labelledby="change-password-title">
      <form
        className="consent-gate__panel rx-form"
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <h2 id="change-password-title">Choose your own password</h2>
        <p className="muted">
          Your clinic gave you a temporary password. Choose one only you know to continue. You will stay signed in on this
          device and be signed out everywhere else.
        </p>
        <label htmlFor="cp-current">Temporary password</label>
        <input
          id="cp-current"
          type="password"
          autoComplete="current-password"
          required
          value={current}
          onChange={(e) => setCurrent(e.target.value)}
        />
        <label htmlFor="cp-new">New password</label>
        <input
          id="cp-new"
          type="password"
          autoComplete="new-password"
          required
          minLength={5}
          value={next}
          onChange={(e) => setNext(e.target.value)}
        />
        <label htmlFor="cp-again">New password again</label>
        <input
          id="cp-again"
          type="password"
          autoComplete="new-password"
          required
          value={again}
          onChange={(e) => setAgain(e.target.value)}
        />
        {mismatch && <small className="error">The two passwords differ.</small>}
        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
        <div className="rx-form__actions">
          <button type="submit" disabled={busy || mismatch || !current || !next || !again}>
            {busy ? "Saving…" : "Save password"}
          </button>
          <button type="button" className="link" onClick={logout}>
            Sign out
          </button>
        </div>
      </form>
    </div>
  );
}
