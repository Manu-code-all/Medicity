import { useEffect } from "react";
import { useQuery } from "@tanstack/react-query";
import { useLocation } from "react-router-dom";
import { portal } from "../../api/endpoints";
import { formatDate } from "../../lib/format";

export function PrescriptionsPage() {
  const { hash } = useLocation();
  const prescriptions = useQuery({ queryKey: ["portal", "prescriptions"], queryFn: portal.prescriptions });

  // Links from a visit point at #rx-<id>. The target only exists once the
  // data has loaded, so the browser's own anchor jump misses it.
  useEffect(() => {
    if (!hash || !prescriptions.data) return;
    document.getElementById(hash.slice(1))?.scrollIntoView({ behavior: "smooth", block: "start" });
  }, [hash, prescriptions.data]);

  return (
    <div className="stack">
      <header>
        <h1 className="portal__title">Prescriptions</h1>
        <p className="muted">
          Your current prescriptions. When a doctor revises one, the revision replaces the original here.
        </p>
      </header>

      {prescriptions.isError && <p className="error">Could not load your prescriptions.</p>}
      {prescriptions.isPending && <div className="card skeleton" style={{ height: 180 }} />}
      {prescriptions.data?.length === 0 && (
        <div className="card empty">
          <h2>No prescriptions yet</h2>
          <p className="muted">Prescriptions from your visits will appear here.</p>
        </div>
      )}

      {prescriptions.data?.map((rx) => (
        <article
          key={rx.id}
          id={`rx-${rx.id}`}
          className={hash === `#rx-${rx.id}` ? "card rx is-target" : "card rx"}
        >
          <header className="rx__head">
            <div>
              <p className="eyebrow">
                {formatDate(rx.issuedAt)} · {rx.doctorName} · {rx.specialization}
              </p>
              <h2>{rx.diagnosis}</h2>
            </div>
            <div className="rx__badges">
              {rx.revised && (
                <span className="badge badge--revised" title="This replaces an earlier prescription from the same visit">
                  Revised
                </span>
              )}
              {rx.dispensedAt ? (
                <span className="badge badge--completed">Dispensed {formatDate(rx.dispensedAt)}</span>
              ) : (
                <span className="badge">Not yet dispensed</span>
              )}
            </div>
          </header>

          {rx.items.length > 0 ? (
            <div className="table-wrap">
              <table className="rx__table">
                <thead>
                  <tr>
                    <th scope="col">Medicine</th>
                    <th scope="col">Dose</th>
                    <th scope="col">How often</th>
                    <th scope="col">For</th>
                    <th scope="col">Qty</th>
                  </tr>
                </thead>
                <tbody>
                  {rx.items.map((item) => (
                    <tr key={item.medicine}>
                      <td>
                        <strong>{item.medicine}</strong>
                        <span className="muted"> {item.form.toLowerCase()}</span>
                      </td>
                      <td>{item.dosage}</td>
                      <td>{item.frequency}</td>
                      <td>{item.durationDays} days</td>
                      <td>{item.quantity}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="muted">No medicines on this prescription.</p>
          )}

          {rx.notes && (
            <p className="rx__notes">
              <strong>Doctor's notes: </strong>
              {rx.notes}
            </p>
          )}
        </article>
      ))}
    </div>
  );
}
