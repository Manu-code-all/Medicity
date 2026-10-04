import { Link, NavLink, Outlet } from "react-router-dom";
import { useAuth } from "../../auth/context";
import { initials } from "../../lib/format";

export function PharmacistLayout() {
  const { session } = useAuth();

  // The API refuses everyone else anyway; this only explains why the page is empty.
  if (session && session.role !== "PHARMACIST") {
    return (
      <div className="content">
        <div className="card empty">
          <h1>This area is for pharmacists</h1>
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
              <span className="muted">Pharmacist</span>
            </div>
          </div>
        )}
        <nav aria-label="Pharmacist">
          <NavLink
            to="/pharmacist"
            end
            className={({ isActive }) => (isActive ? "portal__link is-active" : "portal__link")}
          >
            My shop
          </NavLink>
        </nav>
      </aside>
      <div className="portal__main">
        <Outlet />
      </div>
    </div>
  );
}
