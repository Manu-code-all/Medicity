import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../../api/client";
import { pharmacy, storeWorkspace } from "../../api/endpoints";
import type { Medicine, StockView } from "../../api/types";
import { formatDate, formatTime } from "../../lib/format";

const KEY = ["store", "stock"];

/**
 * Optional live stock. Most stores never need this: answering questions by
 * hand works without it. A store that keeps stock in billing software can send
 * it here (or through the API) and have questions answered at once.
 */
export function StockPage() {
  const stock = useQuery({ queryKey: KEY, queryFn: storeWorkspace.stock });
  const catalogue = useQuery({ queryKey: ["pharmacy", "medicines"], queryFn: pharmacy.medicines, staleTime: 5 * 60_000 });

  if (stock.isPending || catalogue.isPending) return <div className="card skeleton" style={{ height: 240 }} />;
  if (stock.isError || catalogue.isError) return <p className="error">Could not load your stock.</p>;
  return <StockEditor view={stock.data} medicines={catalogue.data.content} />;
}

interface Row {
  quantity: string;
  unitPrice: string;
}

function StockEditor({ view, medicines }: { view: StockView; medicines: Medicine[] }) {
  const queryClient = useQueryClient();
  const [rows, setRows] = useState<Record<string, Row>>(() =>
    Object.fromEntries(view.items.map((i) => [i.medicineId, { quantity: String(i.quantity), unitPrice: String(i.unitPrice) }])),
  );
  const save = useMutation({
    mutationFn: () =>
      storeWorkspace.replaceStock(
        Object.entries(rows)
          .filter(([, r]) => r.quantity !== "" && r.unitPrice !== "")
          .map(([medicineId, r]) => ({ medicineId, quantity: Number(r.quantity), unitPrice: Number(r.unitPrice) })),
      ),
    onSuccess: (saved) => queryClient.setQueryData(KEY, saved),
  });
  const auto = useMutation({
    mutationFn: (enabled: boolean) => storeWorkspace.autoAnswer(enabled),
    onSuccess: (saved) => queryClient.setQueryData(KEY, saved),
  });

  function set(id: string, patch: Partial<Row>) {
    setRows((prev) => ({ ...prev, [id]: { quantity: "", unitPrice: "", ...prev[id], ...patch } }));
  }

  return (
    <div className="stack">
      <header>
        <h1 className="portal__title">Live stock (optional)</h1>
        <p className="muted">
          You never have to keep this up to date: answering questions by hand works without it. If your billing
          software knows your stock, send it here and questions are answered the moment they arrive.
        </p>
      </header>

      <section className="card">
        <label className="check">
          <input
            type="checkbox"
            checked={view.autoAnswer}
            disabled={auto.isPending}
            onChange={(e) => auto.mutate(e.target.checked)}
          />
          Answer questions automatically from this stock
        </label>
        <p className="muted small">
          {view.updatedAt
            ? `Last sent ${formatDate(view.updatedAt)} at ${formatTime(view.updatedAt)}. `
            : "No stock sent yet. "}
          {view.fresh
            ? "Fresh: automatic answers are on while it stays under a day old."
            : "Stock older than a day never answers automatically; questions wait for you instead."}
        </p>
      </section>

      <form
        className="card"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <div className="table-wrap">
          <table className="rx__table">
            <thead>
              <tr>
                <th scope="col">Medicine</th>
                <th scope="col">In stock</th>
                <th scope="col">Price each (₹)</th>
              </tr>
            </thead>
            <tbody>
              {medicines.map((m) => (
                <tr key={m.id}>
                  <td>
                    <strong>
                      {m.name} {m.strength}
                    </strong>{" "}
                    <span className="muted">{m.genericName}</span>
                  </td>
                  <td>
                    <input
                      aria-label={`${m.name} ${m.strength ?? ""} in stock`}
                      type="number"
                      min={0}
                      value={rows[m.id]?.quantity ?? ""}
                      onChange={(e) => set(m.id, { quantity: e.target.value })}
                    />
                  </td>
                  <td>
                    <input
                      aria-label={`${m.name} ${m.strength ?? ""} price`}
                      type="number"
                      min={0}
                      step="0.01"
                      value={rows[m.id]?.unitPrice ?? ""}
                      onChange={(e) => set(m.id, { unitPrice: e.target.value })}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {save.isError && (
          <p className="error" role="alert">
            {save.error instanceof ApiError ? save.error.message : "Could not save your stock."}
          </p>
        )}
        <button type="submit" disabled={save.isPending}>
          {save.isPending ? "Sending…" : "Send stock"}
        </button>
        <p className="muted small">
          Sending replaces the whole list; a medicine left blank counts as out of stock. Billing software can send the
          same list to <code>PUT /api/v1/stores/me/stock</code>.
        </p>
      </form>
    </div>
  );
}
