import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../../api/client";
import { storeReservations } from "../../api/endpoints";
import type { StoreReservation } from "../../api/types";
import { formatDate, formatTime, relativeFromNow } from "../../lib/format";
import { formatRupees } from "../../lib/requests";

/** What to keep aside, and handing it over against the patient's code. */
export function ReservationsPage() {
  const [show, setShow] = useState<"held" | "done">("held");
  const list = useQuery({
    queryKey: ["store", "reservations", show],
    queryFn: () => storeReservations.list(show),
    refetchInterval: show === "held" ? 30_000 : false,
  });

  return (
    <div className="stack">
      <header>
        <h1 className="portal__title">Pick-ups</h1>
        <p className="muted">
          Keep these aside. The patient shows a six-digit code; type it in to hand over. You never see the code
          beforehand, so it proves the right person is at the counter.
        </p>
      </header>

      <div className="tabs" role="tablist">
        {(["held", "done"] as const).map((tab) => (
          <button
            key={tab}
            type="button"
            role="tab"
            aria-selected={show === tab}
            className={show === tab ? "tab is-active" : "tab"}
            onClick={() => setShow(tab)}
          >
            {tab === "held" ? "Keep aside" : "Finished"}
          </button>
        ))}
      </div>

      {list.isError && <p className="error">Could not load reservations.</p>}
      {list.isPending && <div className="card skeleton" style={{ height: 140 }} />}
      {list.data?.length === 0 && (
        <div className="card empty">
          <h2>{show === "held" ? "Nothing to keep aside" : "Nothing finished yet"}</h2>
        </div>
      )}
      <ul className="store-list">
        {list.data?.map((r) => (
          <li key={r.id}>
            <Reservation reservation={r} />
          </li>
        ))}
      </ul>
    </div>
  );
}

function Reservation({ reservation: r }: { reservation: StoreReservation }) {
  const queryClient = useQueryClient();
  const [code, setCode] = useState("");
  const collect = useMutation({
    mutationFn: () => storeReservations.collect(r.id, code),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["store", "reservations"] }),
    // A wrong code changes the attempts left; show the server's count.
    onError: () => setCode(""),
  });

  return (
    <article className="card">
      <div className="compare__head">
        <div>
          <h2>{r.patientName}</h2>
          <p className="muted">
            {r.status === "HELD"
              ? `Keep until ${formatTime(r.expiresAt)} (${relativeFromNow(r.expiresAt)})`
              : r.status === "COLLECTED"
                ? `Collected ${formatDate(r.collectedAt!)} ${formatTime(r.collectedAt!)}`
                : r.status === "EXPIRED"
                  ? "Not collected in time"
                  : "Cancelled by the patient"}
          </p>
        </div>
        <strong>{formatRupees(r.total)}</strong>
      </div>

      <table className="compare__lines">
        <tbody>
          {r.lines.map((line) => (
            <tr key={line.prescribedAs}>
              <td>
                <strong>
                  {line.name} {line.strength}
                </strong>
                {line.name !== line.prescribedAs && (
                  <span className="compare__substitute">in place of {line.prescribedAs}</span>
                )}
              </td>
              <td>
                × {line.quantity}
                {line.quantity < line.asked && <span className="muted"> of {line.asked}</span>}
              </td>
              <td className="num">{formatRupees(line.unitPrice * line.quantity)}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {r.status === "HELD" &&
        (r.codeLocked ? (
          <p className="error">Too many wrong codes. The patient can cancel and reserve again from the app.</p>
        ) : (
          <form
            className="collect"
            onSubmit={(e) => {
              e.preventDefault();
              collect.mutate();
            }}
          >
            <label htmlFor={`code-${r.id}`}>Patient's code</label>
            <input
              id={`code-${r.id}`}
              inputMode="numeric"
              autoComplete="off"
              pattern="[0-9]{6}"
              maxLength={6}
              required
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
            />
            <button type="submit" disabled={collect.isPending || code.length !== 6}>
              Hand over
            </button>
            {collect.isError && (
              <span className="error" role="alert">
                {collect.error instanceof ApiError ? collect.error.message : "Could not record the pick-up."}
              </span>
            )}
          </form>
        ))}
    </article>
  );
}
