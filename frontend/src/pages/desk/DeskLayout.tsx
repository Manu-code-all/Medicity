import { Link } from "react-router-dom";
import { WorkspaceShell } from "../../components/WorkspaceShell";
import { useAuth } from "../../auth/context";
import { initials } from "../../lib/format";

const SECTIONS = [
  { to: "/desk", label: "Today", end: true },
  { to: "/desk/walk-in", label: "Walk-in", end: false },
  { to: "/desk/book", label: "Book a visit", end: false },
];

/** The front desk's workspace. Receptionists, and the clinic's administrator when they cover the desk. */
export function DeskLayout() {
  const { session } = useAuth();

  // The API refuses everyone else; this only explains the empty page.
  if (session && session.role !== "RECEPTIONIST" && session.role !== "CLINIC_ADMIN") {
    return (
      <div className="content">
        <div className="card empty">
          <h1>This workspace is for a clinic's front desk</h1>
          <p className="muted">You are signed in as {session.role.toLowerCase().replace("_", " ")}.</p>
          <Link to="/">Go to the home page</Link>
        </div>
      </div>
    );
  }

  const stations =
    session?.role === "CLINIC_ADMIN" ? [...SECTIONS, { to: "/clinic", label: "My clinic", end: false }] : SECTIONS;

  return (
    <WorkspaceShell
      label="Front desk"
      who={
        session && (
          <div className="ws__who">
            <div className="avatar avatar--sm" aria-hidden="true">
              {initials(session.fullName)}
            </div>
            <div>
              <strong>{session.fullName}</strong>
              <span className="muted">{session.role === "CLINIC_ADMIN" ? "Clinic administrator" : "Receptionist"}</span>
            </div>
          </div>
        )
      }
      stations={stations}
    />
  );
}
