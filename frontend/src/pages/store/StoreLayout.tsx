import { Link } from "react-router-dom";
import { WorkspaceShell } from "../../components/WorkspaceShell";
import { useAuth } from "../../auth/context";
import { initials } from "../../lib/format";

const SECTIONS = [
  { to: "/store/requests", label: "Questions", end: false },
  { to: "/store/reservations", label: "Pick-ups", end: false },
  { to: "/store/insights", label: "Insights", end: false },
  { to: "/store/stock", label: "Live stock", end: false },
  { to: "/store", label: "My store", end: true },
];

/** The chemist's workspace. */
export function StoreLayout() {
  const { session } = useAuth();

  // The API refuses everyone else; this only explains the empty page.
  if (session && session.role !== "CHEMIST") {
    return (
      <div className="content">
        <div className="card empty">
          <h1>This workspace is for chemists</h1>
          <p className="muted">You are signed in as {session.role.toLowerCase()}.</p>
          <Link to="/">Go to the home page</Link>
        </div>
      </div>
    );
  }

  return (
    <WorkspaceShell
      label="Store workspace"
      who={
        session && (
          <div className="ws__who">
            <div className="avatar avatar--sm" aria-hidden="true">
              {initials(session.fullName)}
            </div>
            <div>
              <strong>{session.fullName}</strong>
              <span className="muted">Chemist</span>
            </div>
          </div>
        )
      }
      stations={SECTIONS}
    />
  );
}
