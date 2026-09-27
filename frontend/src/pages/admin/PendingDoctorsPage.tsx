import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { ApiError } from "../../api/client";
import { admin } from "../../api/endpoints";
import { useAuth } from "../../auth/context";
import { formatDate } from "../../lib/format";
import { AdminTabs } from "./AdminTabs";

const PENDING_KEY = ["admin", "doctors", "pending"];

/** Doctors who signed up and wait for their registration number to be checked. */
export function PendingDoctorsPage() {
  const { session } = useAuth();
  const queryClient = useQueryClient();
  const pending = useQuery({ queryKey: PENDING_KEY, queryFn: admin.pendingDoctors, enabled: session?.role === "ADMIN" });
  const verify = useMutation({
    mutationFn: admin.verifyDoctor,
    onSettled: () => queryClient.invalidateQueries({ queryKey: PENDING_KEY }),
  });

  if (session?.role !== "ADMIN") {
    return (
      <div className="card empty">
        <h1>For administrators</h1>
        <Link to="/">Go to the home page</Link>
      </div>
    );
  }

  return (
    <div className="stack">
      <AdminTabs />
      <header>
        <h1>Doctors to verify</h1>
        <p className="muted">
          Look up each registration number in the medical council's register (the National Medical Commission's
          Indian Medical Register, or the state council's) and check the name matches before verifying. A verified
          doctor can be booked by patients.
        </p>
      </header>

      {verify.isError && (
        <p className="error" role="alert">
          {verify.error instanceof ApiError ? verify.error.message : "Could not verify the doctor."}
        </p>
      )}
      {pending.isError && <p className="error">Could not load doctors.</p>}
      {pending.isPending && <div className="card skeleton" style={{ height: 140 }} />}
      {pending.data?.length === 0 && (
        <div className="card empty">
          <h2>Nothing waiting</h2>
          <p className="muted">Every doctor who signed up has been checked.</p>
        </div>
      )}

      {pending.data?.map((d) => (
        <article key={d.id} className="card">
          <p className="muted small">Registered {formatDate(d.registeredAt)}</p>
          <h2>{d.fullName}</h2>
          <dl className="details">
            <dt>Registration number</dt>
            <dd>
              <strong>{d.registrationNumber}</strong>
            </dd>
            <dt>Council</dt>
            <dd>{d.medicalCouncil}</dd>
            <dt>Speciality</dt>
            <dd>
              {d.specialization} · {d.qualification}
            </dd>
            <dt>Experience</dt>
            <dd>{d.yearsExperience} years</dd>
            <dt>Email</dt>
            <dd>{d.email}</dd>
          </dl>
          <button type="button" disabled={verify.isPending} onClick={() => verify.mutate(d.id)}>
            Registration checked: verify doctor
          </button>
        </article>
      ))}
    </div>
  );
}
