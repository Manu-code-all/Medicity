import { useQuery } from "@tanstack/react-query";
import { Link, Navigate, NavLink, Outlet, Route, Routes, useNavigate } from "react-router-dom";
import { notifications } from "./api/endpoints";
import { homeFor, useAuth } from "./auth/context";
import { ProtectedRoute } from "./components/ProtectedRoute";
import { BookingPage } from "./pages/BookingPage";
import { DoctorLayout } from "./pages/doctor/DoctorLayout";
import { PatientHistoryPage } from "./pages/doctor/PatientHistoryPage";
import { SchedulePage } from "./pages/doctor/SchedulePage";
import { VisitPage } from "./pages/doctor/VisitPage";
import { DoctorsPage } from "./pages/DoctorsPage";
import { LandingPage } from "./pages/LandingPage";
import { LoginPage } from "./pages/LoginPage";
import { NOTIFICATIONS_KEY, NotificationsPage } from "./pages/NotificationsPage";
import { RegisterPage } from "./pages/RegisterPage";
import { OverviewPage } from "./pages/portal/OverviewPage";
import { PortalLayout } from "./pages/portal/PortalLayout";
import { PrescriptionsPage } from "./pages/portal/PrescriptionsPage";
import { ProfilePage } from "./pages/portal/ProfilePage";
import { VisitsPage } from "./pages/portal/VisitsPage";

export function App() {
  const { session, logout } = useAuth();
  const navigate = useNavigate();

  function signOut() {
    logout();
    navigate("/", { replace: true });
  }

  return (
    <div className="app">
      <header className="topbar">
        <div className="topbar__inner">
          <Link to="/" className="brand">
            <span className="brand__mark" aria-hidden="true">+</span>
            Medicity
          </Link>
          <nav>
            <NavLink to="/doctors">Find a doctor</NavLink>
            {session ? (
              <>
                {session.role !== "ADMIN" && (
                  <NavLink to={homeFor(session.role)}>
                    {session.role === "DOCTOR" ? "My workspace" : "My portal"}
                  </NavLink>
                )}
                <NotificationsLink />
                <button type="button" className="link" onClick={signOut}>
                  Sign out
                </button>
              </>
            ) : (
              <>
                <NavLink to="/login">Sign in</NavLink>
                <Link className="button button--sm" to="/register">
                  Get started
                </Link>
              </>
            )}
          </nav>
        </div>
      </header>

      <main>
        <Routes>
          {/* Full-bleed pages manage their own width. */}
          <Route path="/" element={<LandingPage />} />

          <Route element={<ProtectedRoute />}>
            <Route path="/portal" element={<PortalLayout />}>
              <Route index element={<OverviewPage />} />
              <Route path="visits" element={<VisitsPage />} />
              <Route path="prescriptions" element={<PrescriptionsPage />} />
              <Route path="profile" element={<ProfilePage />} />
            </Route>
            <Route path="/doctor" element={<DoctorLayout />}>
              <Route index element={<SchedulePage />} />
              <Route path="visits/:visitId" element={<VisitPage />} />
              <Route path="patients/:patientId" element={<PatientHistoryPage />} />
            </Route>
          </Route>

          {/* Everything else sits in a centred column. */}
          <Route element={<Contained />}>
            <Route path="/doctors" element={<DoctorsPage />} />
            <Route path="/login" element={<LoginPage />} />
            <Route path="/register" element={<RegisterPage />} />
            <Route element={<ProtectedRoute />}>
              <Route path="/doctors/:doctorId/book" element={<BookingPage />} />
              <Route path="/notifications" element={<NotificationsPage />} />
            </Route>
            {/* The old URL, kept working for existing bookmarks. */}
            <Route path="/appointments" element={<Navigate to="/portal/visits" replace />} />
            <Route path="*" element={<p className="muted">Page not found.</p>} />
          </Route>
        </Routes>
      </main>
    </div>
  );
}

/**
 * Header link with the unread count. Polled once a minute: notifications are
 * delivered by a background relay a moment after the change, so a push
 * channel would add infrastructure for no visible gain at this scale.
 */
function NotificationsLink() {
  const list = useQuery({
    queryKey: NOTIFICATIONS_KEY,
    queryFn: notifications.mine,
    refetchInterval: 60_000,
  });
  const unread = list.data?.unread ?? 0;
  return (
    <NavLink to="/notifications" aria-label={unread ? `Notifications, ${unread} unread` : "Notifications"}>
      Notifications{unread > 0 && <span className="count-badge">{unread}</span>}
    </NavLink>
  );
}

function Contained() {
  return (
    <div className="content">
      <Outlet />
    </div>
  );
}
