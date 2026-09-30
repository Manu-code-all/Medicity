import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { ArrowRight, MapPin, Pill, Stethoscope } from "@phosphor-icons/react";
import type { ReactNode } from "react";
import { portal } from "../../api/endpoints";
import { useAuth } from "../../auth/context";
import { useViewedPerson } from "../../lib/useViewedPerson";
import { dateTile, firstName, formatDate, formatDayLong, formatTime, greeting, relativeFromNow } from "../../lib/format";
import { VisitCard } from "./VisitCard";
import { LiveStatusPill } from "../../components/LiveStatusPill";
import { isLaterToday } from "../../lib/format";

export function OverviewPage() {
  const { session } = useAuth();
  const viewed = useViewedPerson();
  const name = viewed.fullName ?? session?.fullName;

  const summary = useQuery({ queryKey: ["portal", "summary"], queryFn: portal.summary });
  const recent = useQuery({
    queryKey: ["portal", "visits", "past", 0, 3],
    queryFn: () => portal.visits("past", 0, 3),
  });
  const prescriptions = useQuery({ queryKey: ["portal", "prescriptions"], queryFn: portal.prescriptions });
  const courses = useQuery({ queryKey: ["portal", "courses"], queryFn: portal.courses });

  const next = summary.data?.nextVisit;
  const latestRx = prescriptions.data?.[0];
  const rxByAppointment = new Map(prescriptions.data?.map((p) => [p.appointmentId, p.id]));
  const taking = courses.data?.filter((c) => c.status === "TAKING" || c.status === "RUNNING_OUT") ?? [];
  const runningOut = taking.find((c) => c.status === "RUNNING_OUT");

  return (
    <div className="stack">
      <header>
        <h1 className="portal__title">
          {greeting()}, {name ? firstName(name) : "there"}
        </h1>
        <p className="muted">
          {viewed.isSelf || !name ? "Here is everything about your care, in one place." : `Here is everything about ${firstName(name)}'s care, in one place.`}
        </p>
      </header>

      {summary.isError && <p className="error">Could not load your summary.</p>}

      <div className="bento">
        {summary.isPending ? (
          <div className="tile skeleton b-8" style={{ height: 168 }} />
        ) : next ? (
          <section className="ticket b-8" aria-label="Next visit">
            <div className="ticket__stub">
              <span className="ticket__day">{dateTile(next.scheduledAt).day}</span>
              <span className="ticket__month">{dateTile(next.scheduledAt).month}</span>
              <span className="ticket__time">{formatTime(next.scheduledAt)}</span>
            </div>
            <div className="ticket__body">
              <p className="ticket__when">Next visit, {relativeFromNow(next.scheduledAt)}</p>
              <h2>{next.doctorName}</h2>
              <p className="muted">
                {next.specialization} · {formatDayLong(next.scheduledAt)}
              </p>
              {next.reason && <p>{next.reason}</p>}
              {next.visitType !== "VIDEO" && isLaterToday(next.scheduledAt) && <LiveStatusPill doctorId={next.doctorId} />}
            </div>
            <div className="ticket__action">
              <Link className="button button--quiet" to="/portal/visits">
                Manage
              </Link>
            </div>
          </section>
        ) : (
          <section className="tile tile--empty b-8">
            <h2>No upcoming visits</h2>
            <p className="muted">When you book, your next visit will appear here.</p>
            <Link className="button" to="/doctors">
              Find a doctor
            </Link>
          </section>
        )}

        <nav className="tile tile--flush b-4" aria-label="Shortcuts">
          <Shortcut to="/doctors" icon={<Stethoscope size={20} aria-hidden="true" />} title="Book a visit" hint="Next free times" />
          <Shortcut
            to="/portal/prescriptions"
            icon={<Pill size={20} aria-hidden="true" />}
            title="Ask chemists for medicines"
            hint="One question, every store"
          />
          <Shortcut to="/portal/chemists" icon={<MapPin size={20} aria-hidden="true" />} title="Chemists near you" hint="Verified, with distance" />
        </nav>

        {summary.data && (
          <>
            <Stat label="Upcoming" value={summary.data.upcoming} to="/portal/visits" />
            <Stat label="Completed visits" value={summary.data.completed} to="/portal/visits?tab=past" />
            <Stat label="Prescriptions" value={summary.data.prescriptions} to="/portal/prescriptions" />
            <Stat
              label="Cancelled / missed"
              value={summary.data.cancelled + summary.data.missed}
              to="/portal/visits?tab=past"
            />
          </>
        )}

        <section className="panel b-7">
          <div className="panel__head">
            <h2>Recent visits</h2>
            <Link className="arrow-link" to="/portal/visits?tab=past">
              All history <ArrowRight size={16} aria-hidden="true" />
            </Link>
          </div>
          {recent.isPending && <div className="skeleton panel__skeleton" />}
          {recent.data?.content.length === 0 && <p className="muted panel__none">No past visits yet.</p>}
          <ul className="visit-list">
            {recent.data?.content.map((visit) => (
              <VisitCard key={visit.id} visit={visit} prescriptionId={rxByAppointment.get(visit.id)} />
            ))}
          </ul>
        </section>

        <div className="bento__col b-5">
          <section className="panel">
            <div className="panel__head">
              <h2>Latest prescription</h2>
              <Link className="arrow-link" to="/portal/prescriptions">
                All <ArrowRight size={16} aria-hidden="true" />
              </Link>
            </div>
            {prescriptions.isPending && <div className="skeleton panel__skeleton" />}
            {prescriptions.data?.length === 0 && <p className="muted panel__none">No prescriptions yet.</p>}
            {latestRx && (
              <Link to={`/portal/prescriptions#rx-${latestRx.id}`} className="rx-mini">
                <p className="eyebrow">
                  {formatDate(latestRx.issuedAt)} · {latestRx.doctorName}
                </p>
                <strong>{latestRx.diagnosis}</strong>
                <ul>
                  {latestRx.items.map((item) => (
                    <li key={item.medicine}>
                      {item.medicine} {item.strength} <span className="muted">{item.frequency}</span>
                    </li>
                  ))}
                </ul>
              </Link>
            )}
          </section>

          {courses.data && taking.length > 0 && (
            <Link to="/portal/medicines" className={runningOut ? "tile tile--link tile--warn" : "tile tile--link"}>
              <span className="tile__label">Medicines you are taking</span>
              <strong className="tile__figure">{taking.length}</strong>
              <span className="tile__hint">
                {runningOut
                  ? `${runningOut.medicine} ${runningOut.strength ?? ""} runs out in ${Math.max(runningOut.daysLeft, 0)} ${runningOut.daysLeft === 1 ? "day" : "days"}`
                  : "None running out this week"}
              </span>
              <ArrowRight className="tile__arrow" size={16} aria-hidden="true" />
            </Link>
          )}
        </div>
      </div>
    </div>
  );
}

function Stat({ label, value, to }: { label: string; value: number; to: string }) {
  return (
    <Link to={to} className="tile tile--link b-3">
      <span className="tile__label">{label}</span>
      <strong className="tile__figure">{value}</strong>
      <ArrowRight className="tile__arrow" size={16} aria-hidden="true" />
    </Link>
  );
}

function Shortcut({ to, icon, title, hint }: { to: string; icon: ReactNode; title: string; hint: string }) {
  return (
    <Link to={to} className="shortcut">
      <span className="shortcut__icon">{icon}</span>
      <span className="shortcut__text">
        <strong>{title}</strong>
        <span>{hint}</span>
      </span>
      <ArrowRight className="shortcut__arrow" size={16} aria-hidden="true" />
    </Link>
  );
}
