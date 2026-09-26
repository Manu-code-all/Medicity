import { Link } from "react-router-dom";
import type { Visit } from "../../api/types";
import { dateTile, formatDayLong, formatTime } from "../../lib/format";

const STATUS_LABEL: Record<Visit["status"], string> = {
  BOOKED: "Booked",
  COMPLETED: "Completed",
  CANCELLED: "Cancelled",
  NO_SHOW: "Missed",
};

interface Props {
  visit: Visit;
  /** Present when this visit produced a prescription the patient can open. */
  prescriptionId?: string | undefined;
  onCancel?: ((visit: Visit) => void) | undefined;
  cancelling?: boolean | undefined;
}

export function VisitCard({ visit, prescriptionId, onCancel, cancelling }: Props) {
  const tile = dateTile(visit.scheduledAt);
  // A past visit still marked BOOKED was never closed by the clinic; calling
  // it "Booked" in the history list would read as if it were still coming up.
  const isPast = new Date(visit.scheduledAt) <= new Date();
  const status = visit.status === "BOOKED" && isPast ? "Awaiting update" : STATUS_LABEL[visit.status];

  return (
    <li className={`card visit visit--${visit.status.toLowerCase()}`}>
      <div className="date-tile">
        <span className="date-tile__day">{tile.day}</span>
        <span className="date-tile__month">{tile.month}</span>
      </div>

      <div className="visit__body">
        <div className="visit__head">
          <strong>{visit.doctorName}</strong>
          <span className={`badge badge--${visit.status.toLowerCase()}`}>{status}</span>
        </div>
        <p className="muted visit__meta">
          {visit.specialization} · {formatDayLong(visit.scheduledAt)} · {formatTime(visit.scheduledAt)}–
          {formatTime(visit.endsAt)}
        </p>
        {visit.reason && <p className="visit__reason">{visit.reason}</p>}
        {visit.status === "CANCELLED" && visit.cancelReason && (
          <p className="muted visit__note">Cancelled: {visit.cancelReason}</p>
        )}

        {(prescriptionId || onCancel) && (
          <div className="visit__actions">
            {prescriptionId && (
              <Link to={`/portal/prescriptions#rx-${prescriptionId}`}>View prescription</Link>
            )}
            {onCancel && (
              <button
                type="button"
                className="link link--danger"
                disabled={cancelling}
                onClick={() => onCancel(visit)}
              >
                Cancel visit
              </button>
            )}
          </div>
        )}
      </div>
    </li>
  );
}
