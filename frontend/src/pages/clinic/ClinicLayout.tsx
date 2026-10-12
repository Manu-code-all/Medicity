import { Link } from "react-router-dom";
import { WorkspaceShell } from "../../components/WorkspaceShell";
import { useAuth } from "../../auth/context";
import { initials } from "../../lib/format";

const SECTIONS = [
  { to: "/clinic", label: "My clinic", end: true },
  { to: "/clinic/staff", label: "People", end: false },
  { to: "/clinic/report", label: "Report", end: false },
  { to: "/desk", label: "Front desk", end: false },
];

/** The clinic administrator's workspace: profile, people and the numbers. */
export function ClinicLayout() {
  const { session } = useAuth();

  // The API refuses everyone else; this only explains the empty page.
  if (session && session.role !== "CLINIC_ADMIN") {
    return (
      <div className="content">
        <div className="card empty">
          <h1>This workspace is for clinic administrators</h1>
          <p className="muted">You are signed in as {session.role.toLowerCase().replace("_", " ")}.</p>
          <Link to="/">Go to the home page</Link>
        </div>
      </div>
    );
  }

  return (
    <WorkspaceShell
      label="Clinic workspace"
      who={
        session && (
          <div className="ws__who">
            <div className="avatar avatar--sm" aria-hidden="true">
              {initials(session.fullName)}
            </div>
            <div>
              <strong>{session.fullName}</strong>
              <span className="muted">Clinic administrator</span>
            </div>
          </div>
        )
      }
      stations={SECTIONS}
    />
  );
}
