import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { appointments } from "../api/endpoints";
import type { Appointment } from "../api/types";

export function AppointmentsPage() {
  const queryClient = useQueryClient();

  const query = useQuery({
    queryKey: ["my-appointments"],
    queryFn: () => appointments.mine(),
  });

  const cancel = useMutation({
    mutationFn: (id: string) => appointments.cancel(id, "Cancelled by patient"),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["my-appointments"] });
      // The slot is free again, so any availability list on screen is now stale.
      void queryClient.invalidateQueries({ queryKey: ["slots"] });
    },
  });

  if (query.isPending) return <p className="muted">Loading…</p>;
  if (query.isError) return <p className="error">Could not load your appointments.</p>;

  const rows = query.data.content;

  if (rows.length === 0) {
    return <p className="muted">You have no appointments yet.</p>;
  }

  return (
    <section>
      <h1>My appointments</h1>
      <ul className="appointment-list">
        {rows.map((appointment) => (
          <li key={appointment.id} className="card appointment">
            <div>
              <strong>{formatWhen(appointment.scheduledAt)}</strong>
              <p className="muted">{appointment.reason ?? "No reason given"}</p>
            </div>
            <div className="appointment__action">
              <span className={`badge badge--${appointment.status.toLowerCase()}`}>
                {appointment.status}
              </span>
              {isCancellable(appointment) && (
                <button
                  type="button"
                  className="link"
                  disabled={cancel.isPending}
                  onClick={() => cancel.mutate(appointment.id)}
                >
                  Cancel
                </button>
              )}
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}

function isCancellable(appointment: Appointment): boolean {
  return appointment.status === "BOOKED" && new Date(appointment.scheduledAt) > new Date();
}

function formatWhen(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    weekday: "long",
    day: "numeric",
    month: "long",
    hour: "2-digit",
    minute: "2-digit",
  });
}
