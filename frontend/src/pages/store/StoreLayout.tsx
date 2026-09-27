import { Link, NavLink, Outlet } from "react-router-dom";
import { useAuth } from "../../auth/context";
import { initials } from "../../lib/format";

const SECTIONS = [
  { to: "/store/requests", label: "Questions", end: false },
  { to: "/store/reservations", label: "Pick-ups", end: false },
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
    <div className="portal">
      <aside className="portal__nav">
        {session && (
          <div className="portal__who">
            <div className="avatar avatar--sm" aria-hidden="true">
              {initials(session.fullName)}
            </div>
            <div>
              <strong>{session.fullName}</strong>
              <span className="muted">Chemist</span>
            </div>
          </div>
        )}
        <nav aria-label="Store workspace">
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
      </aside>
      <div className="portal__main">
        <Outlet />
      </div>
    </div>
  );
}
