import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { pharmacy } from "../../api/endpoints";
import type { Medicine, Prescription, PrescriptionDraft, PrescriptionDraftItem } from "../../api/types";

interface Props {
  /** When correcting, the prescription being replaced; its content pre-fills the form. */
  replacing?: Prescription | undefined;
  submitLabel: string;
  busy: boolean;
  error: string | null;
  fieldErrors: Record<string, string>;
  onSubmit: (draft: PrescriptionDraft) => void;
  onCancel: () => void;
}

const EMPTY_ITEM: PrescriptionDraftItem = {
  medicineId: "",
  dosage: "",
  frequency: "",
  durationDays: 5,
  quantity: 10,
};

export function PrescriptionForm(props: Props) {
  const catalogue = useQuery({ queryKey: ["pharmacy", "medicines"], queryFn: pharmacy.medicines, staleTime: 5 * 60_000 });

  // Mount the form only once the catalogue is here, so its starting values
  // (which, for a correction, map medicine names back to catalogue ids) are
  // computed exactly once instead of being patched in after a later render.
  if (catalogue.isPending) return <div className="card skeleton" style={{ height: 160 }} />;
  if (catalogue.isError) return <p className="error">Could not load the medicine catalogue.</p>;
  return <DraftForm {...props} medicines={catalogue.data.content} />;
}

function DraftForm({
  replacing,
  submitLabel,
  busy,
  error,
  fieldErrors,
  onSubmit,
  onCancel,
  medicines,
}: Props & { medicines: Medicine[] }) {
  const [diagnosis, setDiagnosis] = useState(replacing?.diagnosis ?? "");
  const [notes, setNotes] = useState(replacing?.notes ?? "");
  const [items, setItems] = useState<PrescriptionDraftItem[]>(() =>
    replacing
      ? replacing.items.map((item) => ({
          medicineId: medicines.find((m) => m.name === item.medicine && m.strength === item.strength)?.id ?? "",
          dosage: item.dosage,
          frequency: item.frequency,
          durationDays: item.durationDays,
          quantity: item.quantity,
        }))
      : [{ ...EMPTY_ITEM }],
  );

  function update(index: number, patch: Partial<PrescriptionDraftItem>) {
    setItems((prev) => prev.map((item, i) => (i === index ? { ...item, ...patch } : item)));
  }

  return (
    <form
      className="rx-form"
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit({ diagnosis, notes, items });
      }}
    >
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}

      <label htmlFor="rx-diagnosis">Diagnosis</label>
      <input id="rx-diagnosis" required maxLength={500} value={diagnosis} onChange={(e) => setDiagnosis(e.target.value)} />
      {fieldErrors.diagnosis && <span className="error field-error">{fieldErrors.diagnosis}</span>}

      <fieldset className="rx-items">
        <legend>Medicines</legend>
        {items.map((item, index) => (
          <div className="rx-item" key={index}>
            <div className="rx-item__medicine">
              <label htmlFor={`rx-med-${index}`}>Medicine</label>
              <select
                id={`rx-med-${index}`}
                required
                value={item.medicineId}
                onChange={(e) => update(index, { medicineId: e.target.value })}
              >
                <option value="">Choose…</option>
                {medicines.map((m) => (
                  <option key={m.id} value={m.id}>
                    {m.name} {m.strength} · {m.form.toLowerCase()} · {m.quantityOnHand} in stock
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor={`rx-dose-${index}`}>Dose</label>
              <input
                id={`rx-dose-${index}`}
                required
                maxLength={80}
                placeholder="500mg"
                value={item.dosage}
                onChange={(e) => update(index, { dosage: e.target.value })}
              />
            </div>
            <div>
              <label htmlFor={`rx-freq-${index}`}>How often</label>
              <input
                id={`rx-freq-${index}`}
                required
                maxLength={80}
                placeholder="Twice daily after food"
                value={item.frequency}
                onChange={(e) => update(index, { frequency: e.target.value })}
              />
            </div>
            <div>
              <label htmlFor={`rx-days-${index}`}>Days</label>
              <input
                id={`rx-days-${index}`}
                type="number"
                min={1}
                max={365}
                required
                value={item.durationDays}
                onChange={(e) => update(index, { durationDays: Number(e.target.value) })}
              />
            </div>
            <div>
              <label htmlFor={`rx-qty-${index}`}>Qty</label>
              <input
                id={`rx-qty-${index}`}
                type="number"
                min={1}
                max={1000}
                required
                value={item.quantity}
                onChange={(e) => update(index, { quantity: Number(e.target.value) })}
              />
            </div>
            {items.length > 1 && (
              <button
                type="button"
                className="link link--danger rx-item__remove"
                onClick={() => setItems((prev) => prev.filter((_, i) => i !== index))}
              >
                Remove
              </button>
            )}
          </div>
        ))}
        {items.length < 20 && (
          <button type="button" className="link" onClick={() => setItems((prev) => [...prev, { ...EMPTY_ITEM }])}>
            + Add medicine
          </button>
        )}
      </fieldset>

      <label htmlFor="rx-notes">Notes for the patient</label>
      <textarea id="rx-notes" rows={3} maxLength={2000} value={notes} onChange={(e) => setNotes(e.target.value)} />

      <div className="rx-form__actions">
        <button type="submit" disabled={busy}>
          {busy ? "Saving…" : submitLabel}
        </button>
        <button type="button" className="link" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  );
}
