import { useState } from "react";
import type { StoreInput } from "../../api/types";

interface Props {
  value: StoreInput;
  onChange: (next: StoreInput) => void;
  fieldErrors: Record<string, string>;
  /** Field errors from the API are keyed "store.name" on sign-up and "name" on edit. */
  errorPrefix?: string;
  /** The licence is what an administrator verified; it cannot be edited afterwards. */
  licenceLocked?: boolean;
}

/** The store's own details, shared by sign-up and the profile editor. */
export function StoreFields({ value, onChange, fieldErrors, errorPrefix = "", licenceLocked }: Props) {
  const [locating, setLocating] = useState(false);
  const [locateError, setLocateError] = useState<string | null>(null);

  function set<K extends keyof StoreInput>(key: K, v: StoreInput[K]) {
    onChange({ ...value, [key]: v });
  }
  const error = (key: keyof StoreInput) => fieldErrors[errorPrefix + key];

  function fillFromDevice() {
    if (!("geolocation" in navigator)) {
      setLocateError("This browser cannot share its location. Enter the coordinates instead.");
      return;
    }
    setLocating(true);
    setLocateError(null);
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        onChange({
          ...value,
          latitude: Number(pos.coords.latitude.toFixed(6)),
          longitude: Number(pos.coords.longitude.toFixed(6)),
        });
        setLocating(false);
      },
      () => {
        setLocateError("Could not get your location. Enter the coordinates instead.");
        setLocating(false);
      },
      { enableHighAccuracy: true, timeout: 10_000 },
    );
  }

  return (
    <fieldset className="store-fields">
      <legend>Your store</legend>

      <label htmlFor="store-name">Store name</label>
      <input id="store-name" required maxLength={120} value={value.name} onChange={(e) => set("name", e.target.value)} />
      {error("name") && <small className="error">{error("name")}</small>}

      <label htmlFor="store-licence">Drug licence number</label>
      <input
        id="store-licence"
        required
        maxLength={40}
        disabled={licenceLocked}
        value={value.licenceNumber}
        onChange={(e) => set("licenceNumber", e.target.value)}
      />
      <small className="muted">
        {licenceLocked
          ? "The licence was checked when your store was verified, so it cannot be changed here."
          : "Medicity checks this before patients' questions reach you."}
      </small>
      {error("licenceNumber") && <small className="error">{error("licenceNumber")}</small>}

      <label htmlFor="store-phone">Store phone</label>
      <input id="store-phone" required value={value.phone} onChange={(e) => set("phone", e.target.value)} />
      {error("phone") && <small className="error">{error("phone")}</small>}

      <label htmlFor="store-address">Address</label>
      <input
        id="store-address"
        required
        maxLength={200}
        value={value.addressLine}
        onChange={(e) => set("addressLine", e.target.value)}
      />
      {error("addressLine") && <small className="error">{error("addressLine")}</small>}

      <label htmlFor="store-city">City</label>
      <input id="store-city" required maxLength={80} value={value.city} onChange={(e) => set("city", e.target.value)} />

      <div className="field-row">
        <div>
          <label htmlFor="store-lat">Latitude</label>
          <input
            id="store-lat"
            type="number"
            step="0.000001"
            min={-90}
            max={90}
            required
            value={value.latitude}
            onChange={(e) => set("latitude", Number(e.target.value))}
          />
        </div>
        <div>
          <label htmlFor="store-lng">Longitude</label>
          <input
            id="store-lng"
            type="number"
            step="0.000001"
            min={-180}
            max={180}
            required
            value={value.longitude}
            onChange={(e) => set("longitude", Number(e.target.value))}
          />
        </div>
      </div>
      <button type="button" className="link" onClick={fillFromDevice} disabled={locating}>
        {locating ? "Locating…" : "Use my current location (stand in the store)"}
      </button>
      {locateError && <small className="error">{locateError}</small>}

      <label className="check">
        <input type="checkbox" checked={value.open24h} onChange={(e) => set("open24h", e.target.checked)} />
        Open 24 hours
      </label>
      {!value.open24h && (
        <div className="field-row">
          <div>
            <label htmlFor="store-opens">Opens</label>
            <input id="store-opens" type="time" required value={value.opensAt} onChange={(e) => set("opensAt", e.target.value)} />
          </div>
          <div>
            <label htmlFor="store-closes">Closes</label>
            <input
              id="store-closes"
              type="time"
              required
              value={value.closesAt}
              onChange={(e) => set("closesAt", e.target.value)}
            />
          </div>
        </div>
      )}
      <small className="muted">A closing time before the opening time means you are open past midnight.</small>

      <label htmlFor="store-hold">Keep reserved medicines aside for</label>
      <select id="store-hold" value={value.holdHours} onChange={(e) => set("holdHours", Number(e.target.value))}>
        <option value={2}>2 hours</option>
        <option value={3}>3 hours</option>
        <option value={4}>4 hours</option>
      </select>
    </fieldset>
  );
}
