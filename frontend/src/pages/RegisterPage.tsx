import { useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { ApiError } from "../api/client";
import { useAuth } from "../auth/context";

export function RegisterPage() {
  const { register } = useAuth();
  const navigate = useNavigate();
  // Sent here from sign-in on the way to the doctors list? Go back there once the account exists.
  const returnTo = (useLocation().state as { from?: string } | null)?.from;

  const [form, setForm] = useState({
    fullName: "",
    email: "",
    password: "",
    phone: "",
    dateOfBirth: "",
    gender: "UNDISCLOSED",
  });
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  function update(key: keyof typeof form) {
    return (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
      setForm((prev) => ({ ...prev, [key]: e.target.value }));
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setFieldErrors({});
    setBusy(true);
    try {
      await register(form);
      // The health questions come next; they hand on to where the person was headed.
      navigate("/welcome", { replace: true, state: { from: returnTo ?? "/portal" } });
    } catch (err) {
      if (err instanceof ApiError) {
        // Server-side validation is the authority; mirror its field errors
        // rather than duplicating the rules in the browser.
        setFieldErrors(err.fieldErrors);
        setError(Object.keys(err.fieldErrors).length ? null : err.message);
      } else {
        setError("Could not create your account.");
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="card form" onSubmit={onSubmit}>
      <h1>Create an account</h1>
      {error && <p className="error" role="alert">{error}</p>}

      <label htmlFor="fullName">Full name</label>
      <input id="fullName" required value={form.fullName} onChange={update("fullName")} />
      {fieldErrors.fullName && <small className="error">{fieldErrors.fullName}</small>}

      <label htmlFor="email">Email</label>
      <input id="email" type="email" required value={form.email} onChange={update("email")} />
      {fieldErrors.email && <small className="error">{fieldErrors.email}</small>}

      <label htmlFor="password">Password</label>
      <input
        id="password"
        type="password"
        autoComplete="new-password"
        required
        minLength={5}
        value={form.password}
        onChange={update("password")}
      />
      <small className="muted">At least 5 characters. Length beats symbols.</small>
      {fieldErrors.password && <small className="error">{fieldErrors.password}</small>}

      <label htmlFor="dateOfBirth">Date of birth</label>
      <input
        id="dateOfBirth"
        type="date"
        required
        value={form.dateOfBirth}
        onChange={update("dateOfBirth")}
      />
      {fieldErrors.dateOfBirth && <small className="error">{fieldErrors.dateOfBirth}</small>}

      <label htmlFor="gender">Gender</label>
      <select id="gender" value={form.gender} onChange={update("gender")}>
        <option value="FEMALE">Female</option>
        <option value="MALE">Male</option>
        <option value="OTHER">Other</option>
        <option value="UNDISCLOSED">Prefer not to say</option>
      </select>

      <label htmlFor="phone">Mobile number (optional)</label>
      <input id="phone" value={form.phone} onChange={update("phone")} />
      {fieldErrors.phone && <small className="error">{fieldErrors.phone}</small>}

      <button type="submit" disabled={busy}>
        {busy ? "Creating…" : "Create account"}
      </button>
      <p className="muted">
        Run a pharmacy? <Link to="/register/store">Register your store</Link>, free.
      </p>
    </form>
  );
}
