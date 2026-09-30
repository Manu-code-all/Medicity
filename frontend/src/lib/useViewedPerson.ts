import { useQuery } from "@tanstack/react-query";
import { useActingFor } from "../api/acting";
import { family } from "../api/endpoints";
import { FAMILY_KEY } from "./family";

/**
 * Whose records the portal is showing: the account holder's own, or the family
 * member chosen in the switcher. The name is null until the family list loads,
 * so callers fall back to the signed-in account's name.
 */
export function useViewedPerson() {
  const current = useActingFor();
  const members = useQuery({ queryKey: FAMILY_KEY, queryFn: family.list, staleTime: 5 * 60_000 });
  const selected = members.data?.find((m) => (current ? m.patientId === current : m.self));
  return { fullName: selected?.fullName ?? null, isSelf: !selected || selected.self };
}
