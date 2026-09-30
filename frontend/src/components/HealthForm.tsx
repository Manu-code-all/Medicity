import { useState, type FormEvent } from "react";
import { MapPin } from "@phosphor-icons/react";
import type { PatientProfile, ProfileUpdate } from "../api/types";

const BLOOD_GROUPS = ["A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"];

interface Draft {
  bloodGroup: string;
  heightCm: string;
  weightKg: string;
  allergies: string;
  chronicConditions: string;
  currentMedications: string;
  emergencyContact: string;
  addressLine: string;
  city: string;
  home: { lat: number; lng: number } | null;
}

function draftFrom(p: PatientProfile | null): Draft {
  return {
    bloodGroup: p?.bloodGroup ?? "",
    heightCm: p?.heightCm?.toString() ?? "",
    weightKg: p?.weightKg?.toString() ?? "",
    allergies: p?.allergies ?? "",
    chronicConditions: p?.chronicConditions ?? "",
    currentMedications: p?.currentMedications ?? "",
    emergencyContact: p?.emergencyContact ?? "",
    addressLine: p?.addressLine ?? "",
    city: p?.city ?? "",
    home: p?.homeLatitude != null && p?.homeLongitude != null ? { lat: p.homeLatitude, lng: p.homeLongitude } : null,
  };
}

function toUpdate(d: Draft): ProfileUpdate {
  return {
    bloodGroup: d.bloodGroup || null,
    heightCm: d.heightCm ? Number(d.heightCm) : null,
    weightKg: d.weightKg ? Number(d.weightKg) : null,
    allergies: d.allergies || null,
    chronicConditions: d.chronicConditions || null,
    currentMedications: d.currentMedications || null,
    emergencyContact: d.emergencyContact || null,
    addressLine: d.addressLine || null,
    city: d.city || null,
    homeLatitude: d.home?.lat ?? null,
    homeLongitude: d.home?.lng ?? null,
  };
}

/**
 * The questions a clinic asks before it sees someone, and where the person is
 * based. Used at sign-up (skippable) and on the profile page. Every field is
 * optional, and what is sent is the whole form, because the server replaces
 * the record with it.
 */
export function HealthForm({
  profile,
  submitLabel,
  saving,
  error,
  fieldErrors = {},
  onSave,
  onSkip,
}: {
  profile: PatientProfile | null;
  submitLabel: string;
  saving: boolean;
  error?: string | null;
  fieldErrors?: Record<string, string>;
  onSave: (update: ProfileUpdate) => void;
  onSkip?: () => void;
}) {
  const [draft, setDraft] = useState(() => draftFrom(profile));
  const [locating, setLocating] = useState<"idle" | "locating" | "refused">("idle");

  const set = (key: keyof Omit<Draft, "home">) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) =>
    setDraft((d) => ({ ...d, [key]: e.target.value }));

  function locate() {
    if (!("geolocation" in navigator)) {
      setLocating("refused");
      return;
    }
    setLocating("locating");
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        setDraft((d) => ({ ...d, home: { lat: pos.coords.latitude, lng: pos.coords.longitude } }));
        setLocating("idle");
      },
      () => setLocating("refused"),
      { enableHighAccuracy: false, timeout: 10_000, maximumAge: 300_000 },
    );
  }

  function submit(e: FormEvent) {
    e.preventDefault();
    onSave(toUpdate(draft));
  }

  return (
    <form className="health" onSubmit={submit}>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}

      <fieldset className="health__group">
        <legend>Body</legend>
        <div className="health__row">
          <label>
            Blood group
            <select value={draft.bloodGroup} onChange={set("bloodGroup")}>
              <option value="">Not sure</option>
              {BLOOD_GROUPS.map((g) => (
                <option key={g} value={g}>
                  {g}
                </option>
              ))}
            </select>
          </label>
          <label>
            Height (cm)
            <input type="number" inputMode="numeric" min={30} max={260} value={draft.heightCm} onChange={set("heightCm")} />
          </label>
          <label>
            Weight (kg)
            <input type="number" inputMode="decimal" min={1} max={400} step="0.1" value={draft.weightKg} onChange={set("weightKg")} />
          </label>
        </div>
        {(fieldErrors.heightCm || fieldErrors.weightKg || fieldErrors.bloodGroup) && (
          <small className="error">{fieldErrors.heightCm ?? fieldErrors.weightKg ?? fieldErrors.bloodGroup}</small>
        )}
      </fieldset>

      <fieldset className="health__group">
        <legend>Medical history</legend>
        <label>
          Allergies
          <textarea rows={2} maxLength={1000} placeholder="Penicillin, peanuts. Leave empty if none." value={draft.allergies} onChange={set("allergies")} />
        </label>
        <label>
          Long-term conditions
          <textarea rows={2} maxLength={1000} placeholder="Diabetes, asthma, blood pressure" value={draft.chronicConditions} onChange={set("chronicConditions")} />
        </label>
        <label>
          Medicines you take now
          <textarea rows={2} maxLength={1000} placeholder="Name and how often" value={draft.currentMedications} onChange={set("currentMedications")} />
        </label>
        <small className="muted">Your doctor sees this when you book. Nobody else does.</small>
      </fieldset>

      <fieldset className="health__group">
        <legend>Where you are</legend>
        <p className="muted health__why">
          Used to show how far each clinic and chemist is from you. Only a place you choose to save is kept.
        </p>
        <div className="health__locate">
          <button type="button" className="button--quiet" onClick={locate} disabled={locating === "locating"}>
            <MapPin size={16} aria-hidden="true" /> {locating === "locating" ? "Finding you…" : draft.home ? "Update my location" : "Use my current location"}
          </button>
          {draft.home && (
            <span className="health__saved" role="status">
              Location set
              <button type="button" className="link" onClick={() => setDraft((d) => ({ ...d, home: null }))}>
                Remove
              </button>
            </span>
          )}
        </div>
        {locating === "refused" && (
          <small className="error">
            Your browser did not share the location. You can still type your area below and allow location later.
          </small>
        )}
        <div className="health__row health__row--2">
          <label>
            Address
            <input maxLength={200} autoComplete="street-address" value={draft.addressLine} onChange={set("addressLine")} />
          </label>
          <label>
            City
            <input maxLength={80} autoComplete="address-level2" value={draft.city} onChange={set("city")} />
          </label>
        </div>
        <label>
          Emergency contact (mobile number)
          <input inputMode="tel" autoComplete="off" value={draft.emergencyContact} onChange={set("emergencyContact")} />
        </label>
        {fieldErrors.emergencyContact && <small className="error">{fieldErrors.emergencyContact}</small>}
      </fieldset>

      <div className="health__actions">
        <button type="submit" disabled={saving}>
          {saving ? "Saving…" : submitLabel}
        </button>
        {onSkip && (
          <button type="button" className="link" onClick={onSkip}>
            Skip for now
          </button>
        )}
      </div>
    </form>
  );
}
