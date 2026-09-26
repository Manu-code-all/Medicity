import { Link, NavLink, Outlet } from "react-router-dom";
import { useAuth } from "../../auth/context";
import { initials } from "../../lib/format";

const SECTIONS = [
  { to: "/portal", label: "Overview", end: true },
  { to: "/portal/visits", label: "Visits", end: false },
  { to: "/portal/prescriptions", label: "Prescriptions", end: false },
  { to: "/portal/profile", label: "Profile", end: false },
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
          <Link to="/doctors">Go to the doctor directory</Link>
        </div>
      </div>
    );
  }

  return (
    <div className="portal">
      <aside className="portal__nav">
        {session && (
          <div className="portal__who">
            <div className="avatar avatar--sm" aria-hidden="true">
              {initials(session.fullName)}
            </div>
            <div>
              <strong>{session.fullName}</strong>
              <span className="muted">Patient</span>
            </div>
          </div>
        )}
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
