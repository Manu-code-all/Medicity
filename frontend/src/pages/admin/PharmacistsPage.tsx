import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { ApiError } from "../../api/client";
import { admin } from "../../api/endpoints";
import { useAuth } from "../../auth/context";
import { formatDate } from "../../lib/format";
import { AdminTabs } from "./AdminTabs";

const KEY = ["admin", "pharmacists"];

/** The hospital pharmacy's sign-ins: who has one, and adding another. */
export function PharmacistsPage() {
  const { session } = useAuth();
  const queryClient = useQueryClient();
  const isAdmin = session?.role === "ADMIN";
  const list = useQuery({ queryKey: KEY, queryFn: admin.pharmacists, enabled: isAdmin });
  const [fullName, setFullName] = useState("");
  const [email, setEmail] = useState("");
  const [temporaryPassword, setTemporaryPassword] = useState("");
  const create = useMutation({
    mutationFn: () => admin.createPharmacist({ email: email.trim(), fullName: fullName.trim(), temporaryPassword }),
    onSuccess: () => {
      setFullName("");
      setEmail("");
      setTemporaryPassword("");
      void queryClient.invalidateQueries({ queryKey: KEY });
    },
  });

  if (!isAdmin) {
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
        <h1>Pharmacists</h1>
        <p className="muted">
          A pharmacist can dispense prescriptions and restock, and nothing else. Give them the temporary password in
          person or by phone, never by email. The app cannot yet make them change it at first sign-in, so ask them
          to treat it as private.
        </p>
      </header>

      <form
        className="card lm-form"
        onSubmit={(e) => {
          e.preventDefault();
          create.mutate();
        }}
      >
        <label htmlFor="ph-name">Full name</label>
        <input id="ph-name" required maxLength={120} value={fullName} onChange={(e) => setFullName(e.target.value)} />
        <label htmlFor="ph-email">Email</label>
        <input id="ph-email" type="email" required maxLength={254} value={email} onChange={(e) => setEmail(e.target.value)} />
        <label htmlFor="ph-password">Temporary password (at least 10 characters)</label>
        <input
          id="ph-password"
          type="password"
          autoComplete="new-password"
          required
          minLength={10}
          maxLength={128}
          value={temporaryPassword}
          onChange={(e) => setTemporaryPassword(e.target.value)}
        />
        {create.isError && (
          <p className="error" role="alert">
            {create.error instanceof ApiError ? create.error.message : "Could not create the account."}
          </p>
        )}
        {create.isSuccess && <p role="status">Created {create.data.fullName}.</p>}
        <button type="submit" disabled={create.isPending}>
          Create pharmacist
        </button>
      </form>

      {list.isError && <p className="error">Could not load pharmacists.</p>}
      {list.data?.length === 0 && <p className="muted">No pharmacists yet.</p>}
      <ul className="store-list">
        {list.data?.map((p) => (
          <li key={p.id} className="card">
            <strong>{p.fullName}</strong>
            <span className="muted">
              {" "}
              {p.email} · added {formatDate(p.createdAt)}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}
