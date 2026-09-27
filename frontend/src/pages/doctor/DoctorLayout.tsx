import { useQuery } from "@tanstack/react-query";
import { Link, NavLink, Outlet } from "react-router-dom";
import { doctorAccount } from "../../api/endpoints";
import { useAuth } from "../../auth/context";
import { initials } from "../../lib/format";

export function DoctorLayout() {
  const { session } = useAuth();
  const profile = useQuery({
    queryKey: ["doctor", "profile"],
    queryFn: doctorAccount.profile,
    enabled: session?.role === "DOCTOR",
    staleTime: 60_000,
  });

  // The API refuses non-doctors anyway; this only explains why the page is empty.
  if (session && session.role !== "DOCTOR") {
    return (
      <div className="content">
        <div className="card empty">
          <h1>The workspace is for doctors</h1>
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
              <span className="muted">Doctor</span>
            </div>
          </div>
        )}
        <nav aria-label="Doctor workspace">
          <NavLink to="/doctor" end className={({ isActive }) => (isActive ? "portal__link is-active" : "portal__link")}>
            Schedule
          </NavLink>
          <NavLink to="/doctor/hours" className={({ isActive }) => (isActive ? "portal__link is-active" : "portal__link")}>
            Your hours
          </NavLink>
        </nav>
      </aside>
      <div className="portal__main">
        {profile.data && !profile.data.verified && (
          <p className="notice" role="status">
            We are checking registration number <strong>{profile.data.registrationNumber}</strong> with the{" "}
            {profile.data.medicalCouncil}. Until then patients cannot find or book you; set your hours now and they
            open the moment you are verified.
          </p>
        )}
        <Outlet />
      </div>
    </div>
  );
}
