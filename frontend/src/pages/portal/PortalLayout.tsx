import { Link } from "react-router-dom";
import { WorkspaceShell } from "../../components/WorkspaceShell";
import { useAuth } from "../../auth/context";
import { FamilySwitcher } from "./FamilySwitcher";

const SECTIONS = [
  { to: "/portal", label: "Overview", end: true },
  { to: "/portal/visits", label: "Visits", end: false },
  { to: "/portal/queue", label: "Walk-in tokens", end: false },
  { to: "/portal/prescriptions", label: "Prescriptions", end: false },
  { to: "/portal/medicines", label: "My medicines", end: false },
  { to: "/portal/requests", label: "Chemist answers", end: false },
  { to: "/portal/chemists", label: "Chemists nearby", end: false },
  { to: "/portal/profile", label: "Profile", end: false },
  { to: "/portal/family", label: "Family", end: false },
];

export function PortalLayout() {
  const { session } = useAuth();

  // Rendering the portal for a doctor or admin would only produce a screen of
  // 403s; the API is the real gate, this just explains it.
  if (session && session.role !== "PATIENT") {
    return (
      <div className="content">
        <div className="card empty">
          <h1>The portal is for patients</h1>
          <p className="muted">You are signed in as {session.role.toLowerCase()}.</p>
          {session.role === "DOCTOR" ? (
            <Link to="/doctor">Go to your workspace</Link>
          ) : (
            <Link to="/doctors">Go to the doctor directory</Link>
          )}
        </div>
      </div>
    );
  }

  return (
    <WorkspaceShell
      label="Patient portal"
      who={session && <FamilySwitcher holderName={session.fullName} />}
      stations={SECTIONS}
      cta={
        <Link className="button ws__cta" to="/doctors">
          Book a visit
        </Link>
      }
    />
  );
}
