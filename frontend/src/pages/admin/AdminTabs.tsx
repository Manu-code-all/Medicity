import { NavLink } from "react-router-dom";

/** The administrator's two queues, and the pharmacy's sign-ins. */
export function AdminTabs() {
  return (
    <nav className="tabs" aria-label="Verification queues">
      <NavLink to="/admin/stores" className={({ isActive }) => (isActive ? "tab is-active" : "tab")}>
        Stores
      </NavLink>
      <NavLink to="/admin/doctors" className={({ isActive }) => (isActive ? "tab is-active" : "tab")}>
        Doctors
      </NavLink>
      <NavLink to="/admin/pharmacists" className={({ isActive }) => (isActive ? "tab is-active" : "tab")}>
        Pharmacists
      </NavLink>
    </nav>
  );
}
