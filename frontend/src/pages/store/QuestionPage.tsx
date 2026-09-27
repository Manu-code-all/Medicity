import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";
import { ApiError } from "../../api/client";
import { storeQueue } from "../../api/endpoints";
import type { AnswerLineInput, Availability, StoreItem, StoreView } from "../../api/types";
import { formatDate } from "../../lib/format";
import { formatDistance } from "../../lib/geo";
import { formatRupees } from "../../lib/requests";

/** One question, with the prescription behind it, and the store's answer. */
export function QuestionPage() {
  const { requestId = "" } = useParams();
  const view = useQuery({ queryKey: ["store", "question", requestId], queryFn: () => storeQueue.get(requestId) });

  if (view.isPending) return <div className="card skeleton" style={{ height: 260 }} />;
  if (view.isError) {
    return (
      <div className="card empty">
        <h1>Question not found</h1>
        <p className="muted">It may not have been sent to your store.</p>
        <Link to="/store/requests">Back to questions</Link>
      </div>
    );
  }
  return <Question view={view.data} />;
}

function Question({ view }: { view: StoreView }) {
  const rx = view.prescription;
  const canAnswer = view.myStatus === "PENDING" && view.status === "OPEN";

  return (
    <div className="stack">
      <header>
        <p className="eyebrow">
          <Link to="/store/requests">Questions</Link> · {formatDistance(view.distanceM)} from your store
        </p>
        <h1 className="portal__title">{view.patientName} is asking</h1>
      </header>

      <section className="card verified">
        <p className="verified__title">✓ Issued in Medicity by the prescribing doctor</p>
        <p>
          <strong>{rx.doctorName}</strong>, {rx.specialization} · Reg. no. {rx.doctorRegistration}
        </p>
        <p className="muted">
          Issued {formatDate(rx.issuedAt)}
          {rx.revised && " · this is the doctor's corrected version"}
          {rx.hospitalDispensedAt && ` · already dispensed once at the hospital pharmacy on ${formatDate(rx.hospitalDispensedAt)}`}
        </p>
      </section>

      {canAnswer ? (
        <AnswerForm view={view} />
      ) : (
        <AnswerSummary view={view} />
      )}
    </div>
  );
}

interface Draft {
  availability: Availability;
  quantity: string;
  unitPrice: string;
  substituteMedicineId: string;
}

function AnswerForm({ view }: { view: StoreView }) {
  const queryClient = useQueryClient();
  const [note, setNote] = useState("");
  const [drafts, setDrafts] = useState<Record<string, Draft>>(() =>
    Object.fromEntries(
      view.items.map((i) => [i.medicineId, { availability: "YES", quantity: "", unitPrice: "", substituteMedicineId: "" }]),
    ),
  );

  const submit = useMutation({
    mutationFn: () => storeQueue.answer(view.id, { note, lines: view.items.map((i) => toLine(i, drafts[i.medicineId]!)) }),
    onSuccess: (saved) => {
      queryClient.setQueryData(["store", "question", view.id], saved);
      void queryClient.invalidateQueries({ queryKey: ["store", "queue"] });
    },
  });

  function set(id: string, patch: Partial<Draft>) {
    setDrafts((prev) => ({ ...prev, [id]: { ...prev[id]!, ...patch } }));
  }

  return (
    <form
      className="card answer"
      onSubmit={(e) => {
        e.preventDefault();
        submit.mutate();
      }}
    >
      <h2>Do you have these?</h2>
      {view.items.map((item) => {
        const d = drafts[item.medicineId]!;
        return (
          <fieldset key={item.medicineId} className="answer__item">
            <legend>
              <strong>
                {item.name} {item.strength}
              </strong>{" "}
              × {item.quantity} <span className="muted">({item.form.toLowerCase()})</span>
            </legend>
            <p className="muted small">
              {item.dosage} · {item.frequency} · {item.durationDays} days
              {item.substitutionAllowed
                ? ` · doctor allows any brand of ${item.genericName}`
                : " · doctor asked for this brand"}
            </p>
            <div className="answer__choices" role="radiogroup" aria-label={`${item.name} availability`}>
              {(["YES", "PARTIAL", "NO"] as const).map((a) => (
                <label key={a} className="check">
                  <input
                    type="radio"
                    name={`avail-${item.medicineId}`}
                    checked={d.availability === a}
                    onChange={() => set(item.medicineId, { availability: a })}
                  />
                  {a === "YES" ? "Yes, all" : a === "PARTIAL" ? "Partly" : "No"}
                </label>
              ))}
            </div>
            {d.availability !== "NO" && (
              <div className="field-row">
                {d.availability === "PARTIAL" && (
                  <div>
                    <label htmlFor={`qty-${item.medicineId}`}>How many do you have?</label>
                    <input
                      id={`qty-${item.medicineId}`}
                      type="number"
                      min={1}
                      max={item.quantity - 1}
                      required
                      value={d.quantity}
                      onChange={(e) => set(item.medicineId, { quantity: e.target.value })}
                    />
                  </div>
                )}
                <div>
                  <label htmlFor={`price-${item.medicineId}`}>Price per {unitOf(item.form)} (₹)</label>
                  <input
                    id={`price-${item.medicineId}`}
                    type="number"
                    min={0}
                    step="0.01"
                    required
                    value={d.unitPrice}
                    onChange={(e) => set(item.medicineId, { unitPrice: e.target.value })}
                  />
                </div>
                {item.equivalents.length > 0 && (
                  <div>
                    <label htmlFor={`sub-${item.medicineId}`}>Give instead</label>
                    <select
                      id={`sub-${item.medicineId}`}
                      value={d.substituteMedicineId}
                      onChange={(e) => set(item.medicineId, { substituteMedicineId: e.target.value })}
                    >
                      <option value="">{item.name} (as prescribed)</option>
                      {item.equivalents.map((eq) => (
                        <option key={eq.id} value={eq.id}>
                          {eq.name} {eq.strength} (same medicine)
                        </option>
                      ))}
                    </select>
                  </div>
                )}
              </div>
            )}
          </fieldset>
        );
      })}

      <label htmlFor="answer-note">Note for the patient (optional)</label>
      <input
        id="answer-note"
        maxLength={300}
        placeholder="e.g. Rest arrives tomorrow morning"
        value={note}
        onChange={(e) => setNote(e.target.value)}
      />

      {submit.isError && (
        <p className="error" role="alert">
          {submit.error instanceof ApiError ? submit.error.message : "Could not send your answer."}
        </p>
      )}
      <button type="submit" disabled={submit.isPending}>
        {submit.isPending ? "Sending…" : "Send answer"}
      </button>
    </form>
  );
}

function AnswerSummary({ view }: { view: StoreView }) {
  if (view.myStatus === "PENDING") {
    return <div className="notice">This question is {view.status.toLowerCase()} and can no longer be answered.</div>;
  }
  const byMedicine = new Map(view.myAnswer.map((l) => [l.medicineId, l]));
  return (
    <section className="card">
      <h2>Your answer</h2>
      <table className="compare__lines">
        <tbody>
          {view.items.map((item) => {
            const line = byMedicine.get(item.medicineId);
            if (!line) return null;
            return (
              <tr key={item.medicineId}>
                <td>
                  {item.name} × {item.quantity}
                  {line.substituteName && <span className="compare__substitute">as {line.substituteName}</span>}
                </td>
                <td className={`availability availability--${line.availability.toLowerCase()}`}>
                  {line.availability === "YES"
                    ? "Yes"
                    : line.availability === "PARTIAL"
                      ? `${line.quantityAvailable} of ${item.quantity}`
                      : "No"}
                </td>
                <td className="num">{line.unitPrice !== null ? `${formatRupees(line.unitPrice)} each` : "—"}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {view.myNote && <p className="compare__note">“{view.myNote}”</p>}
    </section>
  );
}

function toLine(item: StoreItem, d: Draft): AnswerLineInput {
  const has = d.availability !== "NO";
  return {
    medicineId: item.medicineId,
    availability: d.availability,
    quantity: d.availability === "PARTIAL" ? Number(d.quantity) : null,
    unitPrice: has ? Number(d.unitPrice) : null,
    substituteMedicineId: has && d.substituteMedicineId ? d.substituteMedicineId : null,
  };
}

function unitOf(form: string): string {
  switch (form) {
    case "TABLET":
      return "tablet";
    case "CAPSULE":
      return "capsule";
    default:
      return "unit";
  }
}
