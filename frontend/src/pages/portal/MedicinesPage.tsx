import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { portal } from "../../api/endpoints";
import type { Course, CourseStatus } from "../../api/types";
import { formatDate } from "../../lib/format";

const SECTIONS: { status: CourseStatus; title: string }[] = [
  { status: "RUNNING_OUT", title: "Running out soon" },
  { status: "TAKING", title: "Taking now" },
  { status: "NOT_STARTED", title: "Not collected yet" },
  { status: "FINISHED", title: "Finished" },
];

/**
 * Where each medicine stands, worked out from when it was handed over and
 * how many days it was prescribed for. Long-term medicines that are running
 * out offer to ask the stores again; short courses just say when they end.
 */
export function MedicinesPage() {
  const courses = useQuery({ queryKey: ["portal", "courses"], queryFn: portal.courses });

  return (
    <div className="stack">
      <header>
        <h1 className="portal__title">My medicines</h1>
        <p className="muted">
          Counted from the day your medicines were handed over. You get a reminder three days before a long-term
          medicine runs out, and the day before a course ends.
        </p>
      </header>

      {courses.isError && <p className="error">Could not load your medicines.</p>}
      {courses.isPending && <div className="card skeleton" style={{ height: 160 }} />}
      {courses.data?.length === 0 && (
        <div className="card empty">
          <h2>Nothing prescribed</h2>
          <p className="muted">Medicines from your prescriptions appear here.</p>
        </div>
      )}

      {SECTIONS.map(({ status, title }) => {
        const list = courses.data?.filter((c) => c.status === status) ?? [];
        if (list.length === 0) return null;
        return (
          <section key={status}>
            <h2 className="portal__subtitle">{title}</h2>
            <ul className="course-list">
              {list.map((c) => (
                <CourseRow key={c.prescriptionItemId} course={c} />
              ))}
            </ul>
          </section>
        );
      })}
    </div>
  );
}

function CourseRow({ course: c }: { course: Course }) {
  const taken = c.startedAt ? Math.min(c.durationDays, Math.max(0, c.durationDays - c.daysLeft - 1)) : 0;
  return (
    <li className={`card course course--${c.status.toLowerCase()}`}>
      <div>
        <h3>
          {c.medicine} {c.strength}
        </h3>
        <p className="muted">
          {c.frequency} · {c.durationDays} days
        </p>
        <p className="small">{describe(c)}</p>
        {c.startedAt && c.status !== "FINISHED" && (
          <div
            className="course__bar"
            role="progressbar"
            aria-label={`${c.medicine}: day ${taken + 1} of ${c.durationDays}`}
            aria-valuemin={0}
            aria-valuemax={c.durationDays}
            aria-valuenow={taken}
          >
            <span style={{ width: `${(100 * taken) / c.durationDays}%` }} />
          </div>
        )}
      </div>
      {c.status === "RUNNING_OUT" && (
        <Link className="button" to={`/portal/prescriptions?ask=${c.prescriptionId}`}>
          Ask the chemists again
        </Link>
      )}
      {c.status === "NOT_STARTED" && (
        <Link className="button button--ghost" to={`/portal/prescriptions?ask=${c.prescriptionId}`}>
          Find it nearby
        </Link>
      )}
    </li>
  );
}

function describe(c: Course): string {
  if (!c.startedAt || !c.lastDay) return `Prescribed ${formatDate(c.issuedAt)}; not collected yet.`;
  const from = `Since ${formatDate(c.startedAt)} from ${c.startedWhere}.`;
  if (c.daysLeft < 0) return `Finished ${formatDate(c.lastDay)}.`;
  if (c.daysLeft === 0) return `${from} Last day today.`;
  if (c.daysLeft === 1) return `${from} Last day tomorrow${c.ongoing ? "" : ": finish the course"}.`;
  return `${from} ${c.daysLeft} days left.`;
}
