import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { ApiError } from "../../api/client";
import type { StoreInput } from "../../api/types";
import { useAuth } from "../../auth/context";
import { EMPTY_STORE } from "../../lib/store";
import { StoreFields } from "./StoreFields";

/** A chemist signs up with their store. Free, and checked by a person before it goes live. */
export function StoreRegisterPage() {
  const { registerChemist } = useAuth();
  const navigate = useNavigate();

  const [account, setAccount] = useState({ fullName: "", email: "", password: "" });
  const [store, setStore] = useState<StoreInput>(EMPTY_STORE);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  function update(key: keyof typeof account) {
    return (e: React.ChangeEvent<HTMLInputElement>) => setAccount((prev) => ({ ...prev, [key]: e.target.value }));
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setFieldErrors({});
    setBusy(true);
    try {
      await registerChemist({ ...account, store });
      navigate("/store", { replace: true });
    } catch (err) {
      if (err instanceof ApiError) {
        setFieldErrors(err.fieldErrors);
        setError(Object.keys(err.fieldErrors).length ? "Please check the highlighted fields." : err.message);
      } else {
        setError("Could not register your store.");
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="card form form--wide" onSubmit={onSubmit}>
      <h1>Register your store</h1>
      <p className="muted">
        Free for chemists. Patients nearby send you their doctor's prescription and ask what you have; you answer
        yes, no or partly, with your price. Nothing to keep in sync.
      </p>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}

      <label htmlFor="fullName">Your name</label>
      <input id="fullName" required maxLength={120} value={account.fullName} onChange={update("fullName")} />
      {fieldErrors.fullName && <small className="error">{fieldErrors.fullName}</small>}

      <label htmlFor="email">Email</label>
      <input id="email" type="email" required value={account.email} onChange={update("email")} />
      {fieldErrors.email && <small className="error">{fieldErrors.email}</small>}

      <label htmlFor="password">Password</label>
      <input
        id="password"
        type="password"
        autoComplete="new-password"
        required
        minLength={12}
        value={account.password}
        onChange={update("password")}
      />
      <small className="muted">At least 12 characters.</small>
      {fieldErrors.password && <small className="error">{fieldErrors.password}</small>}

      <StoreFields value={store} onChange={setStore} fieldErrors={fieldErrors} errorPrefix="store." />

      <button type="submit" disabled={busy}>
        {busy ? "Registering…" : "Register store"}
      </button>
      <p className="muted">
        A patient? <Link to="/register">Create a patient account</Link> instead.
      </p>
    </form>
  );
}
