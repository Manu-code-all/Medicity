import { useQuery } from "@tanstack/react-query";
import { storeWorkspace } from "../../api/endpoints";
import { formatDate } from "../../lib/format";

/** What patients near the store asked for this week, and what the store said. */
export function InsightsPage() {
  const insights = useQuery({ queryKey: ["store", "insights"], queryFn: storeWorkspace.insights });

  if (insights.isPending) return <div className="card skeleton" style={{ height: 240 }} />;
  if (insights.isError) return <p className="error">Could not load insights.</p>;
  const { summary, medicines, from } = insights.data;

  return (
    <div className="stack">
      <header>
        <h1 className="portal__title">What people nearby are asking for</h1>
        <p className="muted">
          Since {formatDate(from)}, from questions sent to your store. Counts only: no patient is ever shown, and a
          medicine only one person asked for is left out.
        </p>
      </header>

      <div className="stats">
        <div className="card stat">
          <strong>{summary.questionsReceived}</strong>
          <span className="muted">questions</span>
        </div>
        <div className="card stat">
          <strong>{summary.answered}</strong>
          <span className="muted">answered{summary.answeredAutomatically > 0 && ` (${summary.answeredAutomatically} automatic)`}</span>
        </div>
        <div className="card stat">
          <strong>{summary.reservations}</strong>
          <span className="muted">reserved with you</span>
        </div>
        <div className="card stat">
          <strong>{summary.medianMinutesToAnswer ?? "–"}</strong>
          <span className="muted">minutes to answer (median)</span>
        </div>
      </div>

      {medicines.length === 0 ? (
        <div className="card empty">
          <h2>Not enough questions yet</h2>
          <p className="muted">As patients nearby ask, the medicines they ask for most appear here.</p>
        </div>
      ) : (
        <section className="card">
          <div className="table-wrap">
            <table className="rx__table">
              <thead>
                <tr>
                  <th scope="col">Medicine</th>
                  <th scope="col">Asked by</th>
                  <th scope="col">You had it</th>
                  <th scope="col">Partly / no</th>
                  <th scope="col">Went elsewhere</th>
                </tr>
              </thead>
              <tbody>
                {medicines.map((m) => (
                  <tr key={m.medicineId}>
                    <td>
                      <strong>
                        {m.name} {m.strength}
                      </strong>
                      {m.considerStocking && <span className="rx__substitute">Consider stocking</span>}
                    </td>
                    <td>
                      {m.patients} people · {m.units} units
                    </td>
                    <td>{m.had}</td>
                    <td>
                      {m.partly} / {m.saidNo}
                      {m.unanswered > 0 && <span className="muted"> ({m.unanswered} unanswered)</span>}
                    </td>
                    <td>{m.wentElsewhere}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {medicines.some((m) => m.considerStocking) && (
            <p className="muted small">
              “Consider stocking”: you said no or partly at least twice this week, to different people.
            </p>
          )}
        </section>
      )}
    </div>
  );
}
