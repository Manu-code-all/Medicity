import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../../api/client";
import { pharmacy } from "../../api/endpoints";

/** What is at or below its reorder level, and adding received stock. */
export function PharmacyStockPage() {
  const low = useQuery({ queryKey: ["pharmacy", "low"], queryFn: pharmacy.lowStock });
  const [search, setSearch] = useState("");
  const [submitted, setSubmitted] = useState("");
  const results = useQuery({
    queryKey: ["pharmacy", "search", submitted],
    queryFn: () => pharmacy.search(submitted),
    enabled: submitted.length > 0,
  });

  return (
    <div className="stack">
      <header>
        <h1 className="portal__title">Stock</h1>
        <p className="muted">Medicines at or below their reorder level. Enter what was received to add it.</p>
      </header>

      {low.isError && <p className="error">Could not load low stock.</p>}
      {low.isPending && <div className="card skeleton" style={{ height: 120 }} />}
      {low.data?.length === 0 && (
        <div className="card empty">
          <h2>Nothing is low</h2>
        </div>
      )}
      <ul className="store-list">
        {low.data?.map((m) => (
          <li key={m.medicineId}>
            <RestockRow
              medicineId={m.medicineId}
              label={`${m.name} ${m.strength ?? ""}`.trim()}
              detail={`${m.quantityOnHand} on hand, reorder at ${m.reorderLevel}`}
            />
          </li>
        ))}
      </ul>

      <section className="stack">
        <h2>Restock another medicine</h2>
        <form
          className="collect"
          onSubmit={(e) => {
            e.preventDefault();
            setSubmitted(search.trim());
          }}
        >
          <label htmlFor="med-search">Medicine name</label>
          <input id="med-search" autoComplete="off" value={search} onChange={(e) => setSearch(e.target.value)} />
          <button type="submit" disabled={search.trim() === ""}>
            Search
          </button>
        </form>
        {results.data?.content.length === 0 && <p className="muted">No medicine matches.</p>}
        <ul className="store-list">
          {results.data?.content.map((m) => (
            <li key={m.id}>
              <RestockRow
                medicineId={m.id}
                label={`${m.name} ${m.strength ?? ""}`.trim()}
                detail={`${m.quantityOnHand} on hand`}
              />
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}

function RestockRow({ medicineId, label, detail }: { medicineId: string; label: string; detail: string }) {
  const queryClient = useQueryClient();
  const [quantity, setQuantity] = useState("");
  const restock = useMutation({
    mutationFn: () => pharmacy.restock(medicineId, Number(quantity)),
    onSuccess: () => {
      setQuantity("");
      void queryClient.invalidateQueries({ queryKey: ["pharmacy"] });
    },
  });
  const n = Number(quantity);
  const valid = Number.isInteger(n) && n >= 1 && n <= 100_000;

  return (
    <article className="card">
      <h3>{label}</h3>
      <p className="muted">{detail}</p>
      <form
        className="collect"
        onSubmit={(e) => {
          e.preventDefault();
          restock.mutate();
        }}
      >
        <label htmlFor={`qty-${medicineId}`}>{`Received, ${label}`}</label>
        <input
          id={`qty-${medicineId}`}
          inputMode="numeric"
          autoComplete="off"
          value={quantity}
          onChange={(e) => setQuantity(e.target.value.replace(/\D/g, ""))}
        />
        <button type="submit" disabled={restock.isPending || !valid}>
          Add stock
        </button>
        {restock.isSuccess && <span role="status">Now {restock.data.quantityOnHand} on hand.</span>}
        {restock.isError && (
          <span className="error" role="alert">
            {restock.error instanceof ApiError ? restock.error.message : "Could not add the stock."}
          </span>
        )}
      </form>
    </article>
  );
}
