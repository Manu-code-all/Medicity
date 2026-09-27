import { useQuery } from "@tanstack/react-query";
import { useActingFor } from "../api/acting";
import { family } from "../api/endpoints";
import { FAMILY_KEY } from "../lib/family";

/** "Booking for Lalitha Nair": said wherever an action is taken for a family member. */
export function ActingBanner({ verb }: { verb: string }) {
  const actingFor = useActingFor();
  const members = useQuery({
    queryKey: FAMILY_KEY,
    queryFn: family.list,
    enabled: actingFor !== null,
    staleTime: 5 * 60_000,
  });
  const member = members.data?.find((m) => m.patientId === actingFor);
  if (!actingFor || !member) return null;
  return (
    <p className="acting-banner" role="status">
      {verb} for {member.fullName}
    </p>
  );
}
