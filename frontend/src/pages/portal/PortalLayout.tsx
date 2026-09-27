import { Link, NavLink, Outlet } from "react-router-dom";
import { useAuth } from "../../auth/context";
import { FamilySwitcher } from "./FamilySwitcher";

const SECTIONS = [
  { to: "/portal", label: "Overview", end: true },
  { to: "/portal/visits", label: "Visits", end: false },
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
    <div className="portal">
      <aside className="portal__nav">
        {session && <FamilySwitcher holderName={session.fullName} />}
        <nav aria-label="Patient portal">
          {SECTIONS.map((s) => (
            <NavLink
              key={s.to}
              to={s.to}
              end={s.end}
              className={({ isActive }) => (isActive ? "portal__link is-active" : "portal__link")}
            >
              {s.label}
            </NavLink>
          ))}
        </nav>
        <Link className="button portal__book" to="/doctors">
          Book a visit
        </Link>
      </aside>

      <div className="portal__main">
        <Outlet />
      </div>
    </div>
  );
}
