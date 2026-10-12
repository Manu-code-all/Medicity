import { Link } from "react-router-dom";
import { WorkspaceShell } from "../../components/WorkspaceShell";
import { useAuth } from "../../auth/context";
import { initials } from "../../lib/format";

const SECTIONS = [
  { to: "/pharmacy", label: "Counter", end: true },
  { to: "/pharmacy/stock", label: "Stock" },
];

/** The hospital pharmacist's workspace. */
export function PharmacyLayout() {
  const { session } = useAuth();

  // The API refuses everyone else; this only explains the empty page.
  if (session && session.role !== "PHARMACIST") {
    return (
      <div className="content">
        <div className="card empty">
          <h1>The pharmacy is for pharmacists</h1>
          <p className="muted">You are signed in as {session.role.toLowerCase()}.</p>
          <Link to="/">Go to the home page</Link>
        </div>
      </div>
    );
  }

  return (
    <WorkspaceShell
      label="Pharmacy workspace"
      who={
        session && (
          <div className="ws__who">
            <div className="avatar avatar--sm" aria-hidden="true">
              {initials(session.fullName)}
            </div>
            <div>
              <strong>{session.fullName}</strong>
              <span className="muted">Pharmacist</span>
            </div>
          </div>
        )
      }
      stations={SECTIONS}
    />
  );
}
