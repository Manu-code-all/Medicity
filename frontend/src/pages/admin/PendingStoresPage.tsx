import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { ApiError } from "../../api/client";
import { admin } from "../../api/endpoints";
import { useAuth } from "../../auth/context";
import { formatDate } from "../../lib/format";
import { formatStoreHours } from "../../lib/geo";
import { AdminTabs } from "./AdminTabs";

const PENDING_KEY = ["admin", "stores", "pending"];

/** Stores waiting for someone to check their drug licence. */
export function PendingStoresPage() {
  const { session } = useAuth();
  const queryClient = useQueryClient();
  const pending = useQuery({ queryKey: PENDING_KEY, queryFn: admin.pendingStores, enabled: session?.role === "ADMIN" });
  const verify = useMutation({
    mutationFn: admin.verifyStore,
    onSettled: () => queryClient.invalidateQueries({ queryKey: PENDING_KEY }),
  });

  if (session?.role !== "ADMIN") {
    return (
      <div className="card empty">
        <h1>For administrators</h1>
        <Link to="/">Go to the home page</Link>
      </div>
    );
  }

  return (
    <div className="stack">
      <AdminTabs />
      <header>
        <h1>Stores to verify</h1>
        <p className="muted">
          Check each drug licence with the state licensing authority before verifying. A verified store receives
          patients' prescriptions.
        </p>
      </header>

      {verify.isError && (
        <p className="error" role="alert">
          {verify.error instanceof ApiError ? verify.error.message : "Could not verify the store."}
        </p>
      )}
      {pending.isError && <p className="error">Could not load stores.</p>}
      {pending.isPending && <div className="card skeleton" style={{ height: 140 }} />}
      {pending.data?.length === 0 && (
        <div className="card empty">
          <h2>Nothing waiting</h2>
          <p className="muted">Every registered store has been checked.</p>
        </div>
      )}

      {pending.data?.map((store) => (
        <article key={store.id} className="card">
          <p className="eyebrow">Registered {formatDate(store.registeredAt)}</p>
          <h2>{store.name}</h2>
          <dl className="details">
            <dt>Drug licence</dt>
            <dd>
              <strong>{store.licenceNumber}</strong>
            </dd>
            <dt>Owner</dt>
            <dd>
              {store.ownerName} · {store.ownerEmail}
            </dd>
            <dt>Address</dt>
            <dd>
              {store.addressLine}, {store.city}
            </dd>
            <dt>Hours</dt>
            <dd>{formatStoreHours(store)}</dd>
            <dt>Phone</dt>
            <dd>{store.phone}</dd>
          </dl>
          <button type="button" disabled={verify.isPending} onClick={() => verify.mutate(store.id)}>
            Licence checked: verify store
          </button>
        </article>
      ))}
    </div>
  );
}
