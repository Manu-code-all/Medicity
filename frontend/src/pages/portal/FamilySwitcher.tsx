import { useQuery, useQueryClient } from "@tanstack/react-query";
import { actingFor, useActingFor } from "../../api/acting";
import { family } from "../../api/endpoints";
import { initials } from "../../lib/format";
import { FAMILY_KEY, relationshipLabel } from "../../lib/family";

/**
 * Whose portal this is right now: the account holder's or a family member's.
 * Switching drops everything cached for the previous person, so nothing of
 * one person's record is ever shown under another's name.
 */
export function FamilySwitcher({ holderName }: { holderName: string }) {
  const queryClient = useQueryClient();
  const current = useActingFor();
  const members = useQuery({ queryKey: FAMILY_KEY, queryFn: family.list, staleTime: 5 * 60_000 });

  const others = members.data?.filter((m) => !m.self) ?? [];
  const selected = members.data?.find((m) => (current ? m.patientId === current : m.self));
  const name = selected?.fullName ?? holderName;

  function choose(patientId: string) {
    const self = members.data?.find((m) => m.self)?.patientId;
    actingFor.set(patientId === self ? null : patientId);
    // Everything but the family list itself belongs to the previous person.
    queryClient.removeQueries({ predicate: (q) => q.queryKey[0] !== FAMILY_KEY[0] || q.queryKey[1] !== FAMILY_KEY[1] });
  }

  return (
    <div className="switcher">
      <div className="portal__who">
        <div className="avatar avatar--sm" aria-hidden="true">
          {initials(name)}
        </div>
        <div>
          <strong>{name}</strong>
          <span className="muted">{selected && !selected.self ? relationshipLabel(selected.relationship) : "Patient"}</span>
        </div>
      </div>
      {others.length > 0 && members.data && (
        <label>
          <span className="sr-only">Whose records</span>
          <select value={selected?.patientId ?? ""} onChange={(e) => choose(e.target.value)}>
            {members.data.map((m) => (
              <option key={m.patientId} value={m.patientId}>
                {m.self ? `${m.fullName} (you)` : `${m.fullName} · ${relationshipLabel(m.relationship)}`}
              </option>
            ))}
          </select>
        </label>
      )}
      {selected && !selected.self && <span className="switcher__for">Acting for {selected.fullName}</span>}
    </div>
  );
}
