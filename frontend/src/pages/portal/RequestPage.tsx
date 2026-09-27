import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";
import { ApiError } from "../../api/client";
import { medicineRequests } from "../../api/endpoints";
import type { AnswerLine, Comparison, PatientReservation, RequestItem, StoreAnswer } from "../../api/types";
import { formatDate, formatTime } from "../../lib/format";
import { formatDistance } from "../../lib/geo";
import { formatRupees, requestStatusLabel } from "../../lib/requests";

/**
 * Every store's answer to one question, best first. The ranking comes from
 * the server, so every device shows the same order.
 */
export function RequestPage() {
  const { requestId = "" } = useParams();
  const queryClient = useQueryClient();
  const key = ["medicine-requests", requestId];
  const comparison = useQuery({
    queryKey: key,
    queryFn: () => medicineRequests.get(requestId),
    // Answers arrive over the next minutes; refresh while the question is open.
    refetchInterval: (query) => (query.state.data?.status === "OPEN" ? 20_000 : false),
  });
  const onUpdated = (updated: Comparison) => {
    queryClient.setQueryData(key, updated);
    void queryClient.invalidateQueries({ queryKey: ["medicine-requests"], exact: true });
  };
  const close = useMutation({ mutationFn: () => medicineRequests.close(requestId), onSuccess: onUpdated });
  const reserve = useMutation({
    mutationFn: (storeId: string) => medicineRequests.reserve(requestId, storeId),
    onSuccess: onUpdated,
  });
  const cancel = useMutation({
    mutationFn: (reservationId: string) => medicineRequests.cancelReservation(reservationId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: key }),
  });

  if (comparison.isPending) return <div className="card skeleton" style={{ height: 240 }} />;
  if (comparison.isError) return <p className="error">Could not load this question.</p>;
  const c = comparison.data;
  const answered = c.stores.filter((s) => s.answered).length;

  return (
    <div className="stack">
      <header>
        <p className="eyebrow">
          <Link to="/portal/requests">Chemist answers</Link> · asked at {formatTime(c.createdAt)}
        </p>
        <h1 className="portal__title">{c.diagnosis}</h1>
        <p className="muted">
          {c.doctorName}'s prescription · {answered} of {c.storesAsked} stores answered ·{" "}
          <span className={`badge badge--${c.status.toLowerCase()}`}>{requestStatusLabel(c.status)}</span>
        </p>
      </header>

      {c.reservation && (
        <ReservationCard
          reservation={c.reservation}
          cancelling={cancel.isPending}
          onCancel={() => cancel.mutate(c.reservation!.id)}
        />
      )}
      {(reserve.isError || cancel.isError) && (
        <p className="error" role="alert">
          {[reserve.error, cancel.error].find((e) => e instanceof ApiError)?.message ?? "Something went wrong."}
        </p>
      )}

      {answered === 0 && c.status === "OPEN" && (
        <div className="notice">Asked {c.storesAsked} stores. Answers appear here as they come in.</div>
      )}

      <ol className="compare">
        {c.stores.map((store) => (
          <StoreCard
            key={store.storeId}
            store={store}
            items={c.items}
            onReserve={
              c.status === "OPEN" && store.answered && store.medicinesAvailable > 0
                ? () => reserve.mutate(store.storeId)
                : undefined
            }
            reserving={reserve.isPending}
          />
        ))}
      </ol>

      {c.status === "OPEN" && (
        <div>
          {close.isError && (
            <p className="error" role="alert">
              {close.error instanceof ApiError ? close.error.message : "Could not close the question."}
            </p>
          )}
          <button type="button" className="link link--danger" disabled={close.isPending} onClick={() => close.mutate()}>
            Close this question
          </button>
          <span className="muted small"> Stores stop seeing it. You can ask again later.</span>
        </div>
      )}
    </div>
  );
}

function ReservationCard({
  reservation: r,
  cancelling,
  onCancel,
}: {
  reservation: PatientReservation;
  cancelling: boolean;
  onCancel: () => void;
}) {
  if (r.status === "HELD") {
    return (
      <section className="card pickup" aria-label="Your reservation">
        <p className="eyebrow">Reserved at {r.storeName}</p>
        <p className="pickup__code" aria-label={`Pick-up code ${r.pickupCode?.split("").join(" ")}`}>
          {r.pickupCode}
        </p>
        <p>
          Show this code at the counter. Kept aside until <strong>{formatTime(r.expiresAt)}</strong>
          {!r.complete && " (what the store had; the rest you will need elsewhere)"}.
        </p>
        <p className="muted">
          {r.storeAddress} · <a href={`tel:${r.storePhone}`}>{r.storePhone}</a> · about {formatRupees(r.total)}, pay at
          the store
        </p>
        <button type="button" className="link link--danger" disabled={cancelling} onClick={onCancel}>
          I no longer need it
        </button>
      </section>
    );
  }
  if (r.status === "COLLECTED") {
    return (
      <div className="notice notice--ok">
        Collected at {r.storeName} on {formatDate(r.collectedAt!)} at {formatTime(r.collectedAt!)}.
      </div>
    );
  }
  return (
    <div className="notice">
      Your reservation at {r.storeName} {r.status === "EXPIRED" ? "expired" : "was cancelled"}. You can reserve again
      while the question is open.
    </div>
  );
}

function StoreCard({
  store,
  items,
  onReserve,
  reserving,
}: {
  store: StoreAnswer;
  items: Comparison["items"];
  onReserve: (() => void) | undefined;
  reserving: boolean;
}) {
  const byMedicine = new Map(store.lines.map((l) => [l.medicineId, l]));
  return (
    <li className={store.complete ? "card compare__store compare__store--complete" : "card compare__store"}>
      <div className="compare__head">
        <div>
          <h2>{store.name}</h2>
          <p className="muted">
            {formatDistance(store.distanceM)} · {store.openNow ? "open now" : "closed now"} · {store.addressLine}
          </p>
        </div>
        <div className="compare__badges">
          {store.complete && <span className="badge badge--completed">Has everything</span>}
          {store.cheapestComplete && <span className="badge badge--booked">Cheapest</span>}
          {store.nearestComplete && <span className="badge badge--booked">Nearest</span>}
        </div>
      </div>

      {store.answered ? (
        <>
          <table className="compare__lines">
            <tbody>
              {items.map((item) => (
                <Line key={item.medicineId} item={item} line={byMedicine.get(item.medicineId)} />
              ))}
            </tbody>
          </table>
          <div className="compare__foot">
            {store.total !== null ? (
              <strong>
                {formatRupees(store.total)}
                {!store.complete && <span className="muted"> for what they have</span>}
              </strong>
            ) : (
              <strong className="muted">Has none of these</strong>
            )}
            <a href={`tel:${store.phone}`}>{store.phone}</a>
            {onReserve && (
              <button type="button" disabled={reserving} onClick={onReserve}>
                Reserve here · kept {store.holdHours} h
              </button>
            )}
          </div>
          {store.note && <p className="compare__note">“{store.note}”</p>}
        </>
      ) : (
        <p className="muted">Not answered yet.</p>
      )}
    </li>
  );
}

function Line({ item, line }: { item: RequestItem; line: AnswerLine | undefined }) {
  if (!line) return null;
  const label =
    line.availability === "YES"
      ? "In stock"
      : line.availability === "PARTIAL"
        ? `${line.quantityAvailable} of ${item.quantity}`
        : "Not in stock";
  return (
    <tr>
      <td>
        <strong>{item.name}</strong> {item.strength} × {item.quantity}
        {line.substituteName && (
          <span className="compare__substitute">
            as {line.substituteName} {line.substituteStrength} (same medicine)
          </span>
        )}
      </td>
      <td className={`availability availability--${line.availability.toLowerCase()}`}>{label}</td>
      <td className="num">
        {line.unitPrice !== null ? `${formatRupees(line.unitPrice * line.quantityAvailable)}` : "—"}
      </td>
    </tr>
  );
}
