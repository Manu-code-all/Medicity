import { useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router-dom";
import { ApiError } from "../../api/client";
import { portal } from "../../api/endpoints";
import type { VisitScope } from "../../api/types";
import { useCancelVisit } from "./useCancelVisit";
import { VisitCard } from "./VisitCard";

const PAGE_SIZE = 10;

export function VisitsPage() {
  // The tab lives in the URL so "All history" links and the back button work.
  const [params, setParams] = useSearchParams();
  const scope: VisitScope = params.get("tab") === "past" ? "past" : "upcoming";
  const [page, setPage] = useState(0);

  const visits = useQuery({
    queryKey: ["portal", "visits", scope, page, PAGE_SIZE],
    queryFn: () => portal.visits(scope, page, PAGE_SIZE),
    placeholderData: keepPreviousData,
  });
  const prescriptions = useQuery({ queryKey: ["portal", "prescriptions"], queryFn: portal.prescriptions });
  const rxByAppointment = new Map(prescriptions.data?.map((p) => [p.appointmentId, p.id]));

  const { cancel, isPending: cancelling, error: cancelError } = useCancelVisit();

  function switchTo(next: VisitScope) {
    setPage(0);
    setParams(next === "past" ? { tab: "past" } : {}, { replace: true });
  }

  const data = visits.data;

  return (
    <div className="stack">
      <header>
        <h1 className="portal__title">Visits</h1>
        <p className="muted">Everything you have booked, attended, cancelled or missed.</p>
      </header>

      <div className="tabs" role="tablist" aria-label="Visit list">
        <button
          type="button"
          role="tab"
          aria-selected={scope === "upcoming"}
          className={scope === "upcoming" ? "tab is-active" : "tab"}
          onClick={() => switchTo("upcoming")}
        >
          Upcoming
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={scope === "past"}
          className={scope === "past" ? "tab is-active" : "tab"}
          onClick={() => switchTo("past")}
        >
          History
        </button>
      </div>

      {cancelError && (
        <p className="error" role="alert">
          {cancelError instanceof ApiError ? cancelError.message : "Could not cancel the visit."}
        </p>
      )}
      {visits.isError && <p className="error">Could not load your visits.</p>}
      {visits.isPending && <div className="card skeleton" style={{ height: 110 }} />}

      {data && data.content.length === 0 && (
        <div className="card empty">
          {scope === "upcoming" ? (
            <>
              <h2>Nothing booked</h2>
              <p className="muted">Find a specialist and pick a time that suits you.</p>
              <Link className="button" to="/doctors">
                Find a doctor
              </Link>
            </>
          ) : (
            <>
              <h2>No history yet</h2>
              <p className="muted">Visits appear here once their time has passed.</p>
            </>
          )}
        </div>
      )}

      <ul className="visit-list">
        {data?.content.map((visit) => (
          <VisitCard
            key={visit.id}
            visit={visit}
            prescriptionId={rxByAppointment.get(visit.id)}
            onCancel={scope === "upcoming" ? cancel : undefined}
            cancelling={cancelling}
          />
        ))}
      </ul>

      {data && data.totalPages > 1 && (
        <nav className="pager" aria-label="Pages">
          <button type="button" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
            Previous
          </button>
          <span className="muted">
            Page {page + 1} of {data.totalPages}
          </span>
          <button
            type="button"
            disabled={page + 1 >= data.totalPages}
            onClick={() => setPage((p) => p + 1)}
          >
            Next
          </button>
        </nav>
      )}
    </div>
  );
}
