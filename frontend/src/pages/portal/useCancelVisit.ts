import { useMutation, useQueryClient } from "@tanstack/react-query";
import { appointments } from "../../api/endpoints";
import type { Visit } from "../../api/types";

/** Cancels a visit after confirmation, then refreshes every portal view. */
export function useCancelVisit() {
  const queryClient = useQueryClient();

  const mutation = useMutation({
    mutationFn: (id: string) => appointments.cancel(id, "Cancelled by patient"),
    onSuccess: () => {
      // Counts, next visit and both lists all change, so refresh them together.
      void queryClient.invalidateQueries({ queryKey: ["portal"] });
      // The slot is bookable again; any availability on screen is stale.
      void queryClient.invalidateQueries({ queryKey: ["slots"] });
    },
  });

  function cancel(visit: Visit) {
    const when = new Date(visit.scheduledAt).toLocaleString();
    if (window.confirm(`Cancel your visit with ${visit.doctorName} on ${when}?`)) {
      mutation.mutate(visit.id);
    }
  }

  return { cancel, isPending: mutation.isPending, error: mutation.error };
}
