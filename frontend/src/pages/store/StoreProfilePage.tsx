import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../../api/client";
import { stores } from "../../api/endpoints";
import type { StoreInput, StoreProfile } from "../../api/types";
import { formatDate } from "../../lib/format";
import { formatStoreHours } from "../../lib/geo";
import { storeInputFrom } from "../../lib/store";
import { StoreFields } from "./StoreFields";

const STORE_KEY = ["stores", "me"];

export function StoreProfilePage() {
  const store = useQuery({ queryKey: STORE_KEY, queryFn: stores.mine });

  if (store.isPending) return <div className="card skeleton" style={{ height: 220 }} />;
  if (store.isError) return <p className="error">Could not load your store.</p>;
  return <Profile store={store.data} />;
}

function Profile({ store }: { store: StoreProfile }) {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<StoreInput>(() => storeInputFrom(store));
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const save = useMutation({
    mutationFn: stores.update,
    onSuccess: (saved) => {
      queryClient.setQueryData(STORE_KEY, saved);
      setEditing(false);
      setFieldErrors({});
    },
    onError: (err) => setFieldErrors(err instanceof ApiError ? err.fieldErrors : {}),
  });

  return (
    <div className="stack">
      <header>
        <p className="eyebrow">{store.city}</p>
        <h1 className="portal__title">{store.name}</h1>
      </header>

      {store.verified ? (
        <div className="notice notice--ok">
          Verified {store.verifiedAt && formatDate(store.verifiedAt)}. Patients within reach can send you their
          questions.
        </div>
      ) : (
        <div className="notice">
          <strong>Waiting for verification.</strong> Medicity is checking drug licence {store.licenceNumber}. You will
          get a notification when it is done; until then, patients cannot find your store.
        </div>
      )}

      {editing ? (
        <form
          className="card form form--wide"
          onSubmit={(e) => {
            e.preventDefault();
            save.mutate(draft);
          }}
        >
          {save.isError && (
            <p className="error" role="alert">
              {save.error instanceof ApiError && Object.keys(save.error.fieldErrors).length === 0
                ? save.error.message
                : "Please check the highlighted fields."}
            </p>
          )}
          <StoreFields value={draft} onChange={setDraft} fieldErrors={fieldErrors} licenceLocked />
          <div className="rx-form__actions">
            <button type="submit" disabled={save.isPending}>
              {save.isPending ? "Saving…" : "Save changes"}
            </button>
            <button
              type="button"
              className="link"
              onClick={() => {
                setDraft(storeInputFrom(store));
                setEditing(false);
              }}
            >
              Cancel
            </button>
          </div>
        </form>
      ) : (
        <section className="card">
          <dl className="details">
            <dt>Address</dt>
            <dd>
              {store.addressLine}, {store.city}
            </dd>
            <dt>Hours</dt>
            <dd>
              {formatStoreHours(store)} · {store.openNow ? "open now" : "closed now"}
            </dd>
            <dt>Reserved medicines kept</dt>
            <dd>{store.holdHours} hours</dd>
            <dt>Phone</dt>
            <dd>{store.phone}</dd>
            <dt>Drug licence</dt>
            <dd>{store.licenceNumber}</dd>
            <dt>Location</dt>
            <dd>
              {store.latitude}, {store.longitude}
            </dd>
          </dl>
          <button type="button" onClick={() => setEditing(true)}>
            Edit store details
          </button>
        </section>
      )}
    </div>
  );
}
