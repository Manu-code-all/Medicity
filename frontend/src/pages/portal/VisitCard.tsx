import { Link } from "react-router-dom";
import type { Visit } from "../../api/types";
import { dateTile, formatDayLong, formatTime } from "../../lib/format";
import { ReviewForm } from "./ReviewForm";
import { useState } from "react";
import { FollowUpPanel } from "../../components/FollowUpPanel";

/** How long after a visit ends its free follow-up questions stay open (mirrors the server). */
const FOLLOW_UP_DAYS = 7;

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
  /** Offer "Rate this visit" on a completed visit not yet reviewed. */
  canReview?: boolean | undefined;
}

export function VisitCard({ visit, prescriptionId, onCancel, cancelling, canReview }: Props) {
  const [followUpOpen, setFollowUpOpen] = useState(false);
  const followUpDaysLeft = Math.ceil(
    (new Date(visit.endsAt).getTime() + FOLLOW_UP_DAYS * 86_400_000 - Date.now()) / 86_400_000,
  );
  const followUpAvailable = canReview && visit.status === "COMPLETED" && followUpDaysLeft > 0;
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
          {visit.visitType === "VIDEO" && <span className="badge badge--video">Video</span>}
        </div>
        <p className="muted visit__meta">
          {visit.specialization} · {formatDayLong(visit.scheduledAt)} · {formatTime(visit.scheduledAt)}–
          {formatTime(visit.endsAt)}
        </p>
        {visit.reason && <p className="visit__reason">{visit.reason}</p>}
        {visit.status === "CANCELLED" && visit.cancelReason && (
          <p className="muted visit__note">Cancelled: {visit.cancelReason}</p>
        )}

        {followUpAvailable && (
          <button
            type="button"
            className="followup-badge"
            aria-expanded={followUpOpen}
            onClick={() => setFollowUpOpen((o) => !o)}
          >
            <span aria-hidden="true">💬</span> Free follow-up · {followUpDaysLeft} day{followUpDaysLeft > 1 ? "s" : ""} left
          </button>
        )}
        {followUpOpen && <FollowUpPanel appointmentId={visit.id} side="PATIENT" />}

        {canReview && visit.status === "COMPLETED" &&
          (visit.reviewed ? (
            <p className="muted small">You reviewed this visit. Thank you.</p>
          ) : (
            <ReviewForm visitId={visit.id} doctorName={visit.doctorName} />
          ))}

        {(prescriptionId || onCancel) && (
          <div className="visit__actions">
            {prescriptionId && (
              <Link to={`/portal/prescriptions#rx-${prescriptionId}`}>View prescription</Link>
            )}
            {visit.visitType === "VIDEO" && visit.status === "BOOKED" && onCancel && (
              <Link className="button button--sm" to={`/visits/${visit.id}/video`}>
                Join video
              </Link>
            )}
            {onCancel && visit.status === "BOOKED" && !isPast && (
              <Link to={`/doctors/${visit.doctorId}/book?move=${visit.id}`}>Change time</Link>
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
