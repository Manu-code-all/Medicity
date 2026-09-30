import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { ApiError } from "../api/client";
import { useAuth } from "../auth/context";
import { MEDICAL_COUNCILS, SPECIALITIES } from "../lib/doctors";

const OTHER = "__other";

const EMPTY = {
  fullName: "",
  email: "",
  phone: "",
  password: "",
  specialization: "",
  council: MEDICAL_COUNCILS[1]!,
  otherCouncil: "",
  registrationNumber: "",
  qualification: "",
  yearsExperience: "",
  consultationFee: "",
  bio: "",
};

/**
 * Doctors sign up themselves, as on Practo: the registration number is what
 * an administrator checks before patients can find or book them. Until then
 * the doctor can sign in and set their hours.
 */
export function RegisterDoctorPage() {
  const { registerDoctor } = useAuth();
  const navigate = useNavigate();
  const [form, setForm] = useState(EMPTY);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  function set<K extends keyof typeof EMPTY>(key: K) {
    return (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) =>
      setForm((prev) => ({ ...prev, [key]: e.target.value }));
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setFieldErrors({});
    setBusy(true);
    try {
      await registerDoctor({
        fullName: form.fullName,
        email: form.email,
        phone: form.phone,
        password: form.password,
        specialization: form.specialization,
        medicalCouncil: form.council === OTHER ? form.otherCouncil : form.council,
        registrationNumber: form.registrationNumber,
        qualification: form.qualification,
        yearsExperience: Number(form.yearsExperience),
        consultationFee: Number(form.consultationFee),
        bio: form.bio || undefined,
      });
      navigate("/doctor/hours", { replace: true });
    } catch (err) {
      if (err instanceof ApiError) {
        setFieldErrors(err.fieldErrors);
        setError(Object.keys(err.fieldErrors).length ? "Check the highlighted fields." : err.message);
      } else {
        setError("Could not register. Check your connection and try again.");
      }
    } finally {
      setBusy(false);
    }
  }

  const fieldError = (k: string) => fieldErrors[k] && <small className="error">{fieldErrors[k]}</small>;

  return (
    <form className="card form form--wide" onSubmit={onSubmit}>
      <h1>Join Medicity as a doctor</h1>
      <p className="muted">
        Free, with no fee per prescription. We check your registration number with your medical council before
        patients can find and book you; you can set your hours straight away.
      </p>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}

      <label htmlFor="d-name">Full name</label>
      <input id="d-name" required maxLength={120} placeholder="Dr. " value={form.fullName} onChange={set("fullName")} />
      {fieldError("fullName")}

      <label htmlFor="d-email">Email</label>
      <input id="d-email" type="email" required value={form.email} onChange={set("email")} />
      {fieldError("email")}

      <label htmlFor="d-phone">Mobile number (you can sign in with a code)</label>
      <input id="d-phone" type="tel" inputMode="numeric" required value={form.phone} onChange={set("phone")} />
      {fieldError("phone")}

      <label htmlFor="d-password">Password</label>
      <input
        id="d-password"
        type="password"
        autoComplete="new-password"
        required
        minLength={5}
        value={form.password}
        onChange={set("password")}
      />
      <small className="muted">At least 5 characters.</small>
      {fieldError("password")}

      <label htmlFor="d-spec">Speciality</label>
      <select id="d-spec" required value={form.specialization} onChange={set("specialization")}>
        <option value="" disabled>
          Choose your speciality
        </option>
        {SPECIALITIES.map((s) => (
          <option key={s} value={s}>
            {s}
          </option>
        ))}
      </select>

      <label htmlFor="d-council">Medical council you are registered with</label>
      <select id="d-council" value={form.council} onChange={set("council")}>
        {MEDICAL_COUNCILS.map((c) => (
          <option key={c} value={c}>
            {c}
          </option>
        ))}
        <option value={OTHER}>Another state council</option>
      </select>
      {form.council === OTHER && (
        <>
          <label htmlFor="d-other">Council name</label>
          <input id="d-other" required maxLength={80} value={form.otherCouncil} onChange={set("otherCouncil")} />
        </>
      )}
      {fieldError("medicalCouncil")}

      <label htmlFor="d-reg">Registration number</label>
      <input id="d-reg" required maxLength={40} value={form.registrationNumber} onChange={set("registrationNumber")} />
      <small className="muted">As on your council certificate. It cannot be changed after you register.</small>
      {fieldError("registrationNumber")}

      <label htmlFor="d-qual">Qualifications</label>
      <input
        id="d-qual"
        required
        maxLength={120}
        placeholder="MBBS, MD (Dermatology)"
        value={form.qualification}
        onChange={set("qualification")}
      />
      {fieldError("qualification")}

      <div className="form__row">
        <div>
          <label htmlFor="d-years">Years of practice</label>
          <input id="d-years" type="number" min={0} max={70} required value={form.yearsExperience} onChange={set("yearsExperience")} />
        </div>
        <div>
          <label htmlFor="d-fee">Consultation fee (₹)</label>
          <input id="d-fee" type="number" min={0} step="50" required value={form.consultationFee} onChange={set("consultationFee")} />
        </div>
      </div>

      <label htmlFor="d-bio">About your practice (optional)</label>
      <textarea id="d-bio" rows={3} maxLength={1000} value={form.bio} onChange={set("bio")} />

      <button type="submit" disabled={busy}>
        {busy ? "Registering…" : "Register"}
      </button>
      <p className="muted small">
        Already registered? <Link to="/login/doctor">Sign in</Link>
      </p>
    </form>
  );
}
