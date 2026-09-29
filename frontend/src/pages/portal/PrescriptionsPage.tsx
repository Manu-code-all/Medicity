import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useLocation, useSearchParams } from "react-router-dom";
import { portal } from "../../api/endpoints";
import { formatDate } from "../../lib/format";
import { AskChemists } from "./AskChemists";
import { PhotoViewer } from "../../components/PhotoViewer";
import { HowToTake } from "./HowToTake";

export function PrescriptionsPage() {
  const { hash } = useLocation();
  const prescriptions = useQuery({ queryKey: ["portal", "prescriptions"], queryFn: portal.prescriptions });
  // A refill reminder links here with ?ask=<prescription>, opening the ask panel for it.
  const [params] = useSearchParams();
  const [asking, setAsking] = useState<string | null>(() => params.get("ask"));

  // Links from a visit point at #rx-<id>. The target only exists once the
  // data has loaded, so the browser's own anchor jump misses it.
  useEffect(() => {
    const target = hash ? hash.slice(1) : asking ? `rx-${asking}` : null;
    if (!target || !prescriptions.data) return;
    document.getElementById(target)?.scrollIntoView({ behavior: "smooth", block: "start" });
    // Only on arrival: not every time the panel is opened by hand.
    // eslint-disable-next-line react-hooks/exhaustive-deps
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
              {rx.diagnosisCode && <p className="muted small">ICD-10 {rx.diagnosisCode}</p>}
            </div>
            <div className="rx__badges">
              {rx.revised && (
                <span className="badge badge--revised" title="This replaces an earlier prescription from the same visit">
                  Revised
                </span>
              )}
              {rx.collectedAt ? (
                <span className="badge badge--completed">
                  Collected at {rx.collectedFrom} {formatDate(rx.collectedAt)}
                </span>
              ) : rx.dispensedAt ? (
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
                        {item.substitutionAllowed && (
                          <span
                            className="rx__substitute"
                            title={`Your doctor allows any brand of ${item.genericName} ${item.strength ?? ""}`}
                          >
                            Cheaper brand OK
                          </span>
                        )}
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

          {rx.items.length > 0 && <HowToTake prescription={rx} />}
          {rx.hasPhoto && <PhotoViewer path={`/api/v1/patients/me/prescriptions/${rx.id}/scan`} />}

          {rx.notes && (
            <p className="rx__notes">
              <strong>Doctor's notes: </strong>
              {rx.notes}
            </p>
          )}

          {rx.items.length > 0 &&
            (asking === rx.id ? (
              <div className="rx__ask">
                <AskChemists prescription={rx} />
                <button type="button" className="link" onClick={() => setAsking(null)}>
                  Cancel
                </button>
              </div>
            ) : (
              <div className="rx__actions">
                <button type="button" className="button--ghost" onClick={() => setAsking(rx.id)}>
                  Ask chemists nearby
                </button>
                <span className="muted small">One question to every verified store around you.</span>
              </div>
            ))}
        </article>
      ))}
    </div>
  );
}
