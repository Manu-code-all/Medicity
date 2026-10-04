import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../../api/client";
import { shop } from "../../api/endpoints";
import type { Shop, ShopUpdate } from "../../api/types";
import { LOCATION_FAILURE_MESSAGE, LocationError, currentPoint, roundPoint } from "../../lib/location";

export function ShopPage() {
  const current = useQuery({ queryKey: ["shop"], queryFn: shop.get });

  if (current.isPending) return <div className="card skeleton" style={{ height: 320 }} />;
  if (current.isError) {
    return (
      <p className="error">
        {current.error instanceof ApiError && current.error.status === 404
          ? "No shop is linked to this account yet. Ask an administrator to set it up."
          : "Could not load your shop."}
      </p>
    );
  }
  // Mounted only once the shop is here, so the form starts from the saved values
  // exactly once instead of being patched in by a later render.
  return <ShopForm saved={current.data} />;
}

/** "09:00:00" from the API to the "09:00" an <input type="time"> wants. */
const toInputTime = (t: string) => t.slice(0, 5);

function ShopForm({ saved }: { saved: Shop }) {
  const queryClient = useQueryClient();
  const [form, setForm] = useState({
    name: saved.name,
    phone: saved.phone,
    addressLine: saved.addressLine,
    city: saved.city,
    pincode: saved.pincode ?? "",
    latitude: String(saved.latitude),
    longitude: String(saved.longitude),
    opensAt: toInputTime(saved.opensAt),
    closesAt: toInputTime(saved.closesAt),
  });
  const [locating, setLocating] = useState(false);
  const [locateError, setLocateError] = useState<string | null>(null);
  const [saveNotice, setSaveNotice] = useState(false);

  const save = useMutation({
    mutationFn: (body: ShopUpdate) => shop.update(body),
    onSuccess: (updated) => {
      queryClient.setQueryData(["shop"], updated);
      setSaveNotice(true);
    },
    onError: () => setSaveNotice(false),
  });

  const fieldErrors = (save.error instanceof ApiError && save.error.fieldErrors) || {};
  const formError =
    save.error instanceof ApiError && Object.keys(fieldErrors).length === 0
      ? save.error.message
      : save.isError && !(save.error instanceof ApiError)
        ? "Something went wrong. Please try again."
        : null;

  function set<K extends keyof typeof form>(key: K, value: string) {
    setSaveNotice(false);
    setForm((prev) => ({ ...prev, [key]: value }));
  }

  async function fillFromLocation() {
    setLocating(true);
    setLocateError(null);
    try {
      const point = roundPoint(await currentPoint());
      setSaveNotice(false);
      setForm((prev) => ({ ...prev, latitude: String(point.lat), longitude: String(point.lng) }));
    } catch (error) {
      setLocateError(LOCATION_FAILURE_MESSAGE[error instanceof LocationError ? error.reason : "unavailable"]);
    } finally {
      setLocating(false);
    }
  }

  return (
    <div className="stack">
      <header>
        <h1 className="portal__title">{saved.name}</h1>
        <p className="muted">
          Drug licence {saved.drugLicenceNumber} ·{" "}
          {saved.status === "ACTIVE" ? (
            <span className="badge badge--completed">Visible to patients</span>
          ) : (
            <span className="badge badge--no_show">Hidden from patients</span>
          )}
        </p>
        {saved.status === "SUSPENDED" && (
          <p className="notice">Your shop is hidden from search. Contact an administrator to have it restored.</p>
        )}
      </header>

      <section className="card">
        <h2 className="portal__subtitle">Shop details</h2>
        <form
          className="rx-form"
          onSubmit={(e) => {
            e.preventDefault();
            save.mutate({
              name: form.name,
              phone: form.phone,
              addressLine: form.addressLine,
              city: form.city,
              pincode: form.pincode,
              latitude: Number(form.latitude),
              longitude: Number(form.longitude),
              opensAt: form.opensAt,
              closesAt: form.closesAt,
            });
          }}
        >
          {formError && (
            <p className="error" role="alert">
              {formError}
            </p>
          )}

          <Field id="shop-name" label="Shop name" error={fieldErrors.name}>
            <input id="shop-name" required maxLength={160} value={form.name} onChange={(e) => set("name", e.target.value)} />
          </Field>
          <Field id="shop-phone" label="Phone patients can call" error={fieldErrors.phone}>
            <input
              id="shop-phone"
              required
              type="tel"
              inputMode="tel"
              placeholder="+919876543210"
              value={form.phone}
              onChange={(e) => set("phone", e.target.value)}
            />
          </Field>
          <Field id="shop-address" label="Address" error={fieldErrors.addressLine}>
            <input
              id="shop-address"
              required
              maxLength={200}
              value={form.addressLine}
              onChange={(e) => set("addressLine", e.target.value)}
            />
          </Field>
          <Field id="shop-city" label="City" error={fieldErrors.city}>
            <input id="shop-city" required maxLength={80} value={form.city} onChange={(e) => set("city", e.target.value)} />
          </Field>
          <Field id="shop-pincode" label="Pincode (optional)" error={fieldErrors.pincode}>
            <input id="shop-pincode" maxLength={10} value={form.pincode} onChange={(e) => set("pincode", e.target.value)} />
          </Field>

          <Field id="shop-opens" label="Opens" error={fieldErrors.opensAt}>
            <input id="shop-opens" required type="time" value={form.opensAt} onChange={(e) => set("opensAt", e.target.value)} />
          </Field>
          <Field id="shop-closes" label="Closes" error={fieldErrors.closesAt}>
            <input id="shop-closes" required type="time" value={form.closesAt} onChange={(e) => set("closesAt", e.target.value)} />
          </Field>
          <p className="muted small">If you close after midnight, set a closing time earlier than the opening time.</p>

          <fieldset className="rx-items">
            <legend>Map position</legend>
            <p className="muted small">
              This is how patients near you find the shop. Stand in the shop and use your current position, or
              enter the coordinates yourself.
            </p>
            <div className="actions">
              <button type="button" className="button button--ghost button--sm" onClick={fillFromLocation} disabled={locating}>
                {locating ? "Finding you…" : "Use my current position"}
              </button>
            </div>
            {locateError && (
              <p className="error" role="alert">
                {locateError}
              </p>
            )}
            <Field id="shop-lat" label="Latitude" error={fieldErrors.latitude}>
              <input
                id="shop-lat"
                required
                type="number"
                step="any"
                min={-90}
                max={90}
                value={form.latitude}
                onChange={(e) => set("latitude", e.target.value)}
              />
            </Field>
            <Field id="shop-lng" label="Longitude" error={fieldErrors.longitude}>
              <input
                id="shop-lng"
                required
                type="number"
                step="any"
                min={-180}
                max={180}
                value={form.longitude}
                onChange={(e) => set("longitude", e.target.value)}
              />
            </Field>
          </fieldset>

          <div className="rx-form__actions">
            <button type="submit" className="button" disabled={save.isPending}>
              {save.isPending ? "Saving…" : "Save changes"}
            </button>
            {saveNotice && (
              <span className="accent" role="status">
                Saved
              </span>
            )}
          </div>
        </form>
      </section>
    </div>
  );
}

function Field({
  id,
  label,
  error,
  children,
}: {
  id: string;
  label: string;
  error: string | undefined;
  children: React.ReactNode;
}) {
  return (
    <>
      <label htmlFor={id}>{label}</label>
      {children}
      {error && <span className="error field-error">{error}</span>}
    </>
  );
}
