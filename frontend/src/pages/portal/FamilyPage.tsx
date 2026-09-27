import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../../api/client";
import { family } from "../../api/endpoints";
import type { Relationship } from "../../api/types";
import { initials } from "../../lib/format";
import { FAMILY_KEY, relationshipLabel } from "../../lib/family";

const EMPTY = { fullName: "", relationship: "PARENT" as Relationship, dateOfBirth: "", gender: "UNDISCLOSED", bloodGroup: "" };

/** Parents and children under one sign-in. */
export function FamilyPage() {
  const queryClient = useQueryClient();
  const members = useQuery({ queryKey: FAMILY_KEY, queryFn: family.list });
  const [form, setForm] = useState(EMPTY);
  const [adding, setAdding] = useState(false);
  const add = useMutation({
    mutationFn: () => family.add({ ...form, bloodGroup: form.bloodGroup || undefined }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: FAMILY_KEY });
      setForm(EMPTY);
      setAdding(false);
    },
  });

  return (
    <div className="stack">
      <header>
        <h1 className="portal__title">Family</h1>
        <p className="muted">
          Book visits, ask the chemists and keep prescriptions for your parents and children from your own sign-in.
          Choose whose records you are looking at from the menu on the left.
        </p>
      </header>

      {members.isError && <p className="error">Could not load your family.</p>}
      <ul className="family-list">
        {members.data?.map((m) => (
          <li key={m.patientId} className="card family-member">
            <div className="avatar avatar--sm" aria-hidden="true">
              {initials(m.fullName)}
            </div>
            <div>
              <strong>{m.fullName}</strong>
              <p className="muted">
                {m.self ? "You" : relationshipLabel(m.relationship)} · {m.age} yrs
                {m.bloodGroup && ` · ${m.bloodGroup}`}
              </p>
            </div>
          </li>
        ))}
      </ul>

      {adding ? (
        <form
          className="card form form--wide"
          onSubmit={(e) => {
            e.preventDefault();
            add.mutate();
          }}
        >
          <h2>Add a family member</h2>
          {add.isError && (
            <p className="error" role="alert">
              {add.error instanceof ApiError ? add.error.message : "Could not add them."}
            </p>
          )}
          <label htmlFor="fm-name">Full name</label>
          <input id="fm-name" required maxLength={120} value={form.fullName} onChange={(e) => setForm({ ...form, fullName: e.target.value })} />
          <label htmlFor="fm-rel">They are your</label>
          <select id="fm-rel" value={form.relationship} onChange={(e) => setForm({ ...form, relationship: e.target.value as Relationship })}>
            <option value="PARENT">Parent</option>
            <option value="CHILD">Child</option>
            <option value="SPOUSE">Spouse</option>
            <option value="SIBLING">Sibling</option>
            <option value="OTHER">Other family</option>
          </select>
          <label htmlFor="fm-dob">Date of birth</label>
          <input id="fm-dob" type="date" required value={form.dateOfBirth} onChange={(e) => setForm({ ...form, dateOfBirth: e.target.value })} />
          <label htmlFor="fm-gender">Gender</label>
          <select id="fm-gender" value={form.gender} onChange={(e) => setForm({ ...form, gender: e.target.value })}>
            <option value="FEMALE">Female</option>
            <option value="MALE">Male</option>
            <option value="OTHER">Other</option>
            <option value="UNDISCLOSED">Prefer not to say</option>
          </select>
          <label htmlFor="fm-blood">Blood group (optional)</label>
          <input id="fm-blood" placeholder="O+" maxLength={3} value={form.bloodGroup} onChange={(e) => setForm({ ...form, bloodGroup: e.target.value.toUpperCase() })} />
          <div className="rx-form__actions">
            <button type="submit" disabled={add.isPending}>
              {add.isPending ? "Adding…" : "Add"}
            </button>
            <button type="button" className="link" onClick={() => setAdding(false)}>
              Cancel
            </button>
          </div>
        </form>
      ) : (
        <button type="button" className="button--ghost" onClick={() => setAdding(true)}>
          + Add a family member
        </button>
      )}
    </div>
  );
}
