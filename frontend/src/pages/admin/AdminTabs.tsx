import { NavLink } from "react-router-dom";

/** The administrator's two queues. */
export function AdminTabs() {
  return (
    <nav className="tabs" aria-label="Verification queues">
      <NavLink to="/admin/stores" className={({ isActive }) => (isActive ? "tab is-active" : "tab")}>
        Stores
      </NavLink>
      <NavLink to="/admin/doctors" className={({ isActive }) => (isActive ? "tab is-active" : "tab")}>
        Doctors
      </NavLink>
    </nav>
  );
}
