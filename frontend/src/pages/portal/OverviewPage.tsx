import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { portal } from "../../api/endpoints";
import { useAuth } from "../../auth/context";
import { dateTile, firstName, formatDate, formatDayLong, formatTime, greeting, relativeFromNow } from "../../lib/format";
import { VisitCard } from "./VisitCard";

export function OverviewPage() {
  const { session } = useAuth();

  const summary = useQuery({ queryKey: ["portal", "summary"], queryFn: portal.summary });
  const recent = useQuery({
    queryKey: ["portal", "visits", "past", 0, 3],
    queryFn: () => portal.visits("past", 0, 3),
  });
  const prescriptions = useQuery({ queryKey: ["portal", "prescriptions"], queryFn: portal.prescriptions });

  const next = summary.data?.nextVisit;
  const latestRx = prescriptions.data?.[0];
  const rxByAppointment = new Map(prescriptions.data?.map((p) => [p.appointmentId, p.id]));

  return (
    <div className="stack">
      <header>
        <h1 className="portal__title">
          {greeting()}, {session ? firstName(session.fullName) : "there"}
        </h1>
        <p className="muted">Here is everything about your care, in one place.</p>
      </header>

      {summary.isError && <p className="error">Could not load your summary.</p>}

      {summary.isPending ? (
        <div className="card skeleton" style={{ height: 120 }} />
      ) : next ? (
        <section className="card next-visit" aria-label="Next visit">
          <div className="date-tile date-tile--lg">
            <span className="date-tile__day">{dateTile(next.scheduledAt).day}</span>
            <span className="date-tile__month">{dateTile(next.scheduledAt).month}</span>
          </div>
          <div>
            <p className="eyebrow">Next visit · {relativeFromNow(next.scheduledAt)}</p>
            <h2>{next.doctorName}</h2>
            <p className="muted">
              {next.specialization} · {formatDayLong(next.scheduledAt)} at {formatTime(next.scheduledAt)}
            </p>
            {next.reason && <p>{next.reason}</p>}
          </div>
          <Link className="next-visit__link" to="/portal/visits">
            Manage
          </Link>
        </section>
      ) : (
        summary.data && (
          <section className="card empty">
            <h2>No upcoming visits</h2>
            <p className="muted">When you book, your next visit will appear here.</p>
            <Link className="button" to="/doctors">
              Find a doctor
            </Link>
          </section>
        )
      )}

      {summary.data && (
        <section className="stats" aria-label="Your numbers">
          <Stat label="Upcoming" value={summary.data.upcoming} to="/portal/visits" />
          <Stat label="Completed visits" value={summary.data.completed} to="/portal/visits?tab=past" />
          <Stat label="Prescriptions" value={summary.data.prescriptions} to="/portal/prescriptions" />
          <Stat
            label="Cancelled / missed"
            value={summary.data.cancelled + summary.data.missed}
            to="/portal/visits?tab=past"
          />
        </section>
      )}

      <div className="two-col">
        <section>
          <div className="section__head">
            <h2 className="portal__subtitle">Recent visits</h2>
            <Link to="/portal/visits?tab=past">All history →</Link>
          </div>
          {recent.isPending && <div className="card skeleton" style={{ height: 96 }} />}
          {recent.data?.content.length === 0 && <p className="muted">No past visits yet.</p>}
          <ul className="visit-list">
            {recent.data?.content.map((visit) => (
              <VisitCard key={visit.id} visit={visit} prescriptionId={rxByAppointment.get(visit.id)} />
            ))}
          </ul>
        </section>

        <section>
          <div className="section__head">
            <h2 className="portal__subtitle">Latest prescription</h2>
            <Link to="/portal/prescriptions">All →</Link>
          </div>
          {prescriptions.isPending && <div className="card skeleton" style={{ height: 96 }} />}
          {prescriptions.data?.length === 0 && <p className="muted">No prescriptions yet.</p>}
          {latestRx && (
            <Link to={`/portal/prescriptions#rx-${latestRx.id}`} className="card rx-mini">
              <p className="eyebrow">
                {formatDate(latestRx.issuedAt)} · {latestRx.doctorName}
              </p>
              <strong>{latestRx.diagnosis}</strong>
              <ul>
                {latestRx.items.map((item) => (
                  <li key={item.medicine}>
                    {item.medicine} {item.strength} — <span className="muted">{item.frequency}</span>
                  </li>
                ))}
              </ul>
            </Link>
          )}
        </section>
      </div>
    </div>
  );
}

function Stat({ label, value, to }: { label: string; value: number; to: string }) {
  return (
    <Link to={to} className="card stat">
      <strong>{value}</strong>
      <span className="muted">{label}</span>
    </Link>
  );
}
