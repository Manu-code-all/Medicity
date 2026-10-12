import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../../api/client";
import { pharmacy } from "../../api/endpoints";
import type { PharmacyPrescription } from "../../api/types";
import { formatDate, formatTime } from "../../lib/format";

/**
 * Find a prescription by the reference the patient reads out (or their name)
 * and fill it. The server fills every medicine or none, and at most once.
 */
export function CounterPage() {
  const [text, setText] = useState("");
  const [submitted, setSubmitted] = useState("");
  const found = useQuery({
    queryKey: ["pharmacy", "prescriptions", submitted],
    queryFn: () => pharmacy.findPrescriptions(submitted),
    enabled: submitted.length >= 3,
  });

  return (
    <div className="stack">
      <header>
        <h1 className="portal__title">Counter</h1>
        <p className="muted">
          Ask for the pharmacy reference on the patient's prescription (eight letters and digits), or search by the
          patient's name. A six-digit pick-up code belongs to a neighbourhood chemist and does not work here.
        </p>
      </header>

      <form
        className="collect"
        onSubmit={(e) => {
          e.preventDefault();
          setSubmitted(text.trim());
        }}
      >
        <label htmlFor="rx-query">Reference or patient name</label>
        <input
          id="rx-query"
          autoComplete="off"
          minLength={3}
          maxLength={60}
          required
          value={text}
          onChange={(e) => setText(e.target.value)}
        />
        <button type="submit" disabled={text.trim().length < 3}>
          Find
        </button>
      </form>

      {found.isError && (
        <p className="error" role="alert">
          {found.error instanceof ApiError ? found.error.message : "Could not search."}
        </p>
      )}
      {found.isFetching && <div className="card skeleton" style={{ height: 120 }} />}
      {!found.isFetching && found.data?.length === 0 && (
        <div className="card empty">
          <h2>No prescription found</h2>
          <p className="muted">Check the reference, or try the patient's name.</p>
        </div>
      )}
      {!found.isFetching && found.data?.map((rx) => <Prescription key={rx.id} rx={rx} />)}
    </div>
  );
}

function Prescription({ rx }: { rx: PharmacyPrescription }) {
  const queryClient = useQueryClient();
  const dispense = useMutation({
    mutationFn: () => pharmacy.dispense(rx.id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["pharmacy"] });
    },
  });
  const short = rx.items.filter((i) => i.quantityOnHand < i.quantity);

  return (
    <article className="card">
      <div className="compare__head">
        <div>
          <p className="eyebrow">
            Reference {rx.reference} · issued {formatDate(rx.issuedAt)} · {rx.doctorName}
          </p>
          <h2>{rx.patientName}</h2>
          <p className="muted">{rx.diagnosis}</p>
        </div>
        {rx.dispensedAt ? (
          <span className="badge badge--completed">
            Dispensed {formatDate(rx.dispensedAt)} {formatTime(rx.dispensedAt)}
          </span>
        ) : (
          <span className="badge">To dispense</span>
        )}
      </div>

      <div className="table-wrap">
        <table className="compare__lines">
          <thead>
            <tr>
              <th scope="col">Medicine</th>
              <th scope="col">Directions</th>
              <th scope="col">Give</th>
              <th scope="col">On hand</th>
            </tr>
          </thead>
          <tbody>
            {rx.items.map((i) => (
              <tr key={i.medicineId}>
                <td>
                  <strong>
                    {i.medicine} {i.strength}
                  </strong>
                </td>
                <td>
                  {i.dosage}, {i.frequency}, {i.durationDays} days
                </td>
                <td>× {i.quantity}</td>
                <td className={i.quantityOnHand < i.quantity ? "error" : undefined}>{i.quantityOnHand}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {dispense.isSuccess && (
        <p role="status">
          Dispensed. Remaining: {dispense.data.items.map((l) => `${l.medicine} ${l.remainingStock}`).join(", ")}.
        </p>
      )}
      {dispense.isError && (
        <p className="error" role="alert">
          {dispense.error instanceof ApiError ? dispense.error.message : "Could not dispense."}
        </p>
      )}
      {rx.canDispense && !dispense.isSuccess && (
        <>
          {short.length > 0 && (
            <p className="error">
              Not enough stock for {short.map((i) => i.medicine).join(", ")}. Nothing is dispensed unless every
              medicine can be.
            </p>
          )}
          <button type="button" disabled={dispense.isPending || short.length > 0} onClick={() => dispense.mutate()}>
            {dispense.isPending ? "Dispensing…" : "Dispense all"}
          </button>
        </>
      )}
    </article>
  );
}
