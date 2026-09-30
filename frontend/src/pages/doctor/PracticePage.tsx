import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../../api/client";
import { doctorAccount } from "../../api/endpoints";
import type { Insurer, Practice, ProcedurePrice } from "../../api/types";

const KEY = ["doctor", "practice"];

const KIND_LABEL: Record<Insurer["kind"], string> = {
  PRIVATE: "Private insurers",
  PUBLIC: "Public sector insurers",
  GOVERNMENT: "Government schemes",
};

interface PriceRow {
  procedure: string;
  priceInr: string;
  everyVisit: boolean;
}

/** Fee, bio, insurers and prices: what the directory shows beside the doctor's name. */
export function PracticePage() {
  const practice = useQuery({ queryKey: KEY, queryFn: doctorAccount.practice });
  if (practice.isPending) return <div className="card skeleton" style={{ height: 420 }} />;
  if (practice.isError) return <p className="error">Could not load your practice details.</p>;
  return (
    <div className="stack">
      <header>
        <h1 className="portal__title">Fees and insurance</h1>
        <p className="muted">
          What patients see beside your name in the directory. Changes show at once; visits already booked are not
          affected.
        </p>
      </header>
      <PracticeEditor saved={practice.data} />
    </div>
  );
}

function PracticeEditor({ saved }: { saved: Practice }) {
  const queryClient = useQueryClient();
  const [fee, setFee] = useState(String(saved.consultationFee));
  const [years, setYears] = useState(String(saved.yearsExperience));
  const [bio, setBio] = useState(saved.bio ?? "");
  const [insurers, setInsurers] = useState<Set<string>>(() => new Set(saved.insurers));
  const [prices, setPrices] = useState<PriceRow[]>(() =>
    saved.prices.map((p) => ({ procedure: p.procedure, priceInr: String(p.priceInr), everyVisit: p.everyVisit })),
  );

  const save = useMutation({
    mutationFn: () =>
      doctorAccount.savePractice({
        consultationFee: Number(fee),
        yearsExperience: Number(years),
        bio: bio.trim() || null,
        insurers: [...insurers],
        // Rows left without a name are dropped rather than refused: an empty row is an unfinished thought.
        prices: prices
          .filter((p) => p.procedure.trim())
          .map<ProcedurePrice>((p) => ({ procedure: p.procedure.trim(), priceInr: Number(p.priceInr) || 0, everyVisit: p.everyVisit })),
      }),
    onSuccess: (result) => queryClient.setQueryData(KEY, result),
  });

  function toggle(name: string) {
    setInsurers((prev) => {
      const next = new Set(prev);
      if (next.has(name)) next.delete(name);
      else next.add(name);
      return next;
    });
  }
  function updatePrice(i: number, patch: Partial<PriceRow>) {
    setPrices((prev) => prev.map((p, j) => (j === i ? { ...p, ...patch } : p)));
  }

  const kinds = (["PRIVATE", "PUBLIC", "GOVERNMENT"] as const).filter((k) =>
    saved.availableInsurers.some((i) => i.kind === k),
  );

  return (
    <form
      className="stack"
      onSubmit={(e) => {
        e.preventDefault();
        save.mutate();
      }}
    >
      <section className="card stack" aria-labelledby="practice-fee">
        <h2 id="practice-fee" className="portal__subtitle">
          Consultation
        </h2>
        <div className="practice__row">
          <label>
            Consultation fee (₹)
            <input type="number" min={0} max={100000} step={50} required value={fee} onChange={(e) => setFee(e.target.value)} />
          </label>
          <label>
            Years in practice
            <input type="number" min={0} max={70} required value={years} onChange={(e) => setYears(e.target.value)} />
          </label>
        </div>
        <label>
          About you <span className="muted small">(shown on your listing)</span>
          <textarea rows={3} maxLength={1000} value={bio} onChange={(e) => setBio(e.target.value)} />
        </label>
      </section>

      <section className="card stack" aria-labelledby="practice-insurers">
        <div>
          <h2 id="practice-insurers" className="portal__subtitle">
            Insurance you accept
          </h2>
          <p className="muted small">Patients can filter the directory by these.</p>
        </div>
        {kinds.map((kind) => (
          <fieldset key={kind} className="practice__insurers">
            <legend>{KIND_LABEL[kind]}</legend>
            {saved.availableInsurers
              .filter((i) => i.kind === kind)
              .map((i) => (
                <label key={i.name} className="check">
                  <input type="checkbox" checked={insurers.has(i.name)} onChange={() => toggle(i.name)} />
                  {i.name}
                </label>
              ))}
          </fieldset>
        ))}
      </section>

      <section className="card stack" aria-labelledby="practice-prices">
        <div>
          <h2 id="practice-prices" className="portal__subtitle">
            Other charges
          </h2>
          <p className="muted small">
            Tests and procedures you charge for beyond the fee. Tick "every visit" for a charge everyone pays, such as
            registration; it is added to the fee shown.
          </p>
        </div>
        {prices.length > 0 && (
          <div className="table-wrap">
            <table className="rx__table">
              <thead>
                <tr>
                  <th scope="col">Charge</th>
                  <th scope="col">Price (₹)</th>
                  <th scope="col">Every visit</th>
                  <th scope="col">
                    <span className="sr-only">Remove</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {prices.map((p, i) => (
                  <tr key={i}>
                    <td>
                      <input
                        aria-label={`Charge ${i + 1} name`}
                        maxLength={80}
                        value={p.procedure}
                        onChange={(e) => updatePrice(i, { procedure: e.target.value })}
                      />
                    </td>
                    <td>
                      <input
                        type="number"
                        min={0}
                        max={500000}
                        aria-label={`Charge ${i + 1} price`}
                        value={p.priceInr}
                        onChange={(e) => updatePrice(i, { priceInr: e.target.value })}
                      />
                    </td>
                    <td>
                      <input
                        type="checkbox"
                        aria-label={`Charge ${i + 1} every visit`}
                        checked={p.everyVisit}
                        onChange={(e) => updatePrice(i, { everyVisit: e.target.checked })}
                      />
                    </td>
                    <td>
                      <button
                        type="button"
                        className="link"
                        aria-label={`Remove charge ${i + 1}`}
                        onClick={() => setPrices((prev) => prev.filter((_, j) => j !== i))}
                      >
                        Remove
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {prices.length < 20 && (
          <button
            type="button"
            className="button--quiet button--sm"
            onClick={() => setPrices((prev) => [...prev, { procedure: "", priceInr: "", everyVisit: false }])}
          >
            + Add a charge
          </button>
        )}
      </section>

      {save.isError && (
        <p className="error" role="alert">
          {save.error instanceof ApiError ? save.error.message : "Could not save your details."}
        </p>
      )}
      {save.isSuccess && (
        <p className="notice notice--ok" role="status">
          Saved. The directory shows the new details now.
        </p>
      )}
      <div>
        <button type="submit" disabled={save.isPending}>
          {save.isPending ? "Saving…" : "Save"}
        </button>
      </div>
    </form>
  );
}
