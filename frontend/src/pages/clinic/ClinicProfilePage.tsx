import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../../api/client";
import { clinicAdmin } from "../../api/endpoints";
import type { ClinicProfile, ClinicProfileInput } from "../../api/types";
import { CLINIC_KEY } from "../../lib/clinicKeys";

/** The clinic's own details: what patients see for every doctor who works here. */
export function ClinicProfilePage() {
  const profile = useQuery({ queryKey: CLINIC_KEY, queryFn: clinicAdmin.profile });

  if (profile.isPending) return <div className="card skeleton" style={{ height: 260 }} />;
  if (profile.isError) return <p className="error">Could not load your clinic.</p>;
  return <Editor saved={profile.data} />;
}

const text = (v: string | null | undefined) => v ?? "";
const orNull = (v: string) => (v.trim() ? v.trim() : null);
const numOrNull = (v: string) => (v.trim() === "" ? null : Number(v));

function Editor({ saved }: { saved: ClinicProfile }) {
  const queryClient = useQueryClient();
  const [name, setName] = useState(saved.name);
  const [address, setAddress] = useState(text(saved.address));
  const [city, setCity] = useState(text(saved.city));
  const [latitude, setLatitude] = useState(text(saved.latitude == null ? null : String(saved.latitude)));
  const [longitude, setLongitude] = useState(text(saved.longitude == null ? null : String(saved.longitude)));
  const [phone, setPhone] = useState(text(saved.phone));
  const [email, setEmail] = useState(text(saved.email));
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const save = useMutation({
    mutationFn: () => {
      const input: ClinicProfileInput = {
        name: name.trim(),
        address: orNull(address),
        city: orNull(city),
        latitude: numOrNull(latitude),
        longitude: numOrNull(longitude),
        phone: orNull(phone),
        email: orNull(email),
        timezone: saved.timezone,
      };
      return clinicAdmin.saveProfile(input);
    },
    onSuccess: (next) => {
      queryClient.setQueryData(CLINIC_KEY, next);
      setFieldErrors({});
    },
    onError: (err) => setFieldErrors(err instanceof ApiError ? err.fieldErrors : {}),
  });

  return (
    <div className="stack">
      <header className="page-head">
        <div>
          <p className="eyebrow">{saved.city ?? "Clinic"}</p>
          <h1 className="portal__title">{saved.name}</h1>
          <p className="muted">
            Patients see this name, address and location beside every doctor here, and can filter the directory by it.
          </p>
        </div>
      </header>

      {!saved.active && (
        <div className="notice" role="status">
          This clinic is switched off, so its doctors are not listed and staff cannot sign in to it. Contact Medicity.
        </div>
      )}

      <form
        className="card rx-form"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <label htmlFor="clinic-name">Clinic or hospital name</label>
        <input id="clinic-name" required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} />
        {fieldErrors.name && <small className="error">{fieldErrors.name}</small>}

        <label htmlFor="clinic-address">Address</label>
        <input id="clinic-address" maxLength={200} value={address} onChange={(e) => setAddress(e.target.value)} />

        <div className="field-row">
          <div>
            <label htmlFor="clinic-city">City</label>
            <input id="clinic-city" maxLength={80} value={city} onChange={(e) => setCity(e.target.value)} />
          </div>
          <div>
            <label htmlFor="clinic-phone">Phone</label>
            <input id="clinic-phone" inputMode="tel" value={phone} onChange={(e) => setPhone(e.target.value)} />
            {fieldErrors.phone && <small className="error">{fieldErrors.phone}</small>}
          </div>
        </div>

        <label htmlFor="clinic-email">Email</label>
        <input id="clinic-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
        {fieldErrors.email && <small className="error">{fieldErrors.email}</small>}

        <div className="field-row">
          <div>
            <label htmlFor="clinic-lat">Latitude</label>
            <input id="clinic-lat" inputMode="decimal" value={latitude} onChange={(e) => setLatitude(e.target.value)} />
          </div>
          <div>
            <label htmlFor="clinic-lng">Longitude</label>
            <input id="clinic-lng" inputMode="decimal" value={longitude} onChange={(e) => setLongitude(e.target.value)} />
          </div>
        </div>
        <p className="muted small">
          Both coordinates or neither. They let patients see how far the clinic is. Times on the front desk and in the
          report follow {saved.timezone}.
        </p>

        {save.isError && !Object.keys(fieldErrors).length && (
          <p className="error" role="alert">
            {save.error instanceof ApiError ? save.error.message : "Could not save. Try again."}
          </p>
        )}
        {save.isSuccess && (
          <p role="status" className="muted">
            Saved.
          </p>
        )}
        <div className="rx-form__actions">
          <button type="submit" disabled={save.isPending || !name.trim()}>
            {save.isPending ? "Saving…" : "Save clinic details"}
          </button>
        </div>
      </form>
    </div>
  );
}
