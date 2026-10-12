import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ApiError } from "../../api/client";
import { clinicAdmin } from "../../api/endpoints";
import type { ClinicDayRow } from "../../api/types";
import { addDays, isoDay, shortDay } from "../../lib/dates";

/** Visits per day, no-shows, cancellations and walk-ins over a date range (at most 92 days). */
export function ClinicReportPage() {
  const [from, setFrom] = useState(() => isoDay(addDays(new Date(), -6)));
  const [to, setTo] = useState(() => isoDay(new Date()));
  const valid = from !== "" && to !== "" && from <= to;

  const report = useQuery({
    queryKey: ["clinic", "report", from, to],
    queryFn: () => clinicAdmin.report(from, to),
    enabled: valid,
  });

  return (
    <div className="stack">
      <header>
        <h1 className="portal__title">Report</h1>
        <p className="muted">
          How the clinic's days went. Days follow the clinic's time zone{report.data ? ` (${report.data.timezone})` : ""}.
        </p>
      </header>

      <div className="filters">
        <label htmlFor="report-from">From</label>
        <input id="report-from" type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} />
        <label htmlFor="report-to">To</label>
        <input id="report-to" type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} />
      </div>

      {!valid && <p className="error">Choose a start date that is not after the end date.</p>}
      {valid && report.isPending && <div className="card skeleton" style={{ height: 220 }} />}
      {report.isError && (
        <p className="error" role="alert">
          {report.error instanceof ApiError ? report.error.message : "Could not load the report."}
        </p>
      )}

      {report.data && (
        <section className="card" aria-label="Visits by day">
          <div className="table-wrap">
            <table className="rx__table">
              <thead>
                <tr>
                  <th scope="col">Day</th>
                  <th scope="col">Booked visits</th>
                  <th scope="col">Completed</th>
                  <th scope="col">No-shows</th>
                  <th scope="col">Cancellations</th>
                  <th scope="col">Walk-ins</th>
                  <th scope="col">Booked by the desk</th>
                </tr>
              </thead>
              <tbody>
                {report.data.days.map((d) => (
                  <Row key={d.date} label={shortDay(d.date)} row={d} />
                ))}
              </tbody>
              <tfoot>
                <Row label="Total" row={report.data.totals} strong />
              </tfoot>
            </table>
          </div>
        </section>
      )}
    </div>
  );
}

function Row({ label, row, strong }: { label: string; row: ClinicDayRow; strong?: boolean }) {
  const cell = (n: number | string) => (strong ? <th scope="row">{n}</th> : <td>{n}</td>);
  return (
    <tr>
      {strong ? <th scope="row">{label}</th> : <th scope="row">{label}</th>}
      {strong ? <td><strong>{row.scheduled}</strong></td> : cell(row.scheduled)}
      <td>{row.completed}</td>
      <td>{row.noShows}</td>
      <td>{row.cancellations}</td>
      <td>
        {row.walkIns}
        {row.walkIns > 0 && <span className="muted small"> ({row.walkInsSeen} seen)</span>}
      </td>
      <td>{row.bookedByDesk}</td>
    </tr>
  );
}
