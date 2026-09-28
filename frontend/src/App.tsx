import { useQuery } from "@tanstack/react-query";
import { Link, Navigate, NavLink, Outlet, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { notifications } from "./api/endpoints";
import type { Role } from "./api/types";
import { homeFor, useAuth } from "./auth/context";
import { ProtectedRoute } from "./components/ProtectedRoute";
import { BookingPage } from "./pages/BookingPage";
import { DoctorLayout } from "./pages/doctor/DoctorLayout";
import { HoursPage } from "./pages/doctor/HoursPage";
import { PatientHistoryPage } from "./pages/doctor/PatientHistoryPage";
import { SchedulePage } from "./pages/doctor/SchedulePage";
import { VisitPage } from "./pages/doctor/VisitPage";
import { DoctorsPage } from "./pages/DoctorsPage";
import { LandingPage } from "./pages/LandingPage";
import { LoginPage } from "./pages/LoginPage";
import { NOTIFICATIONS_KEY, NotificationsPage } from "./pages/NotificationsPage";
import { PendingDoctorsPage } from "./pages/admin/PendingDoctorsPage";
import { PendingStoresPage } from "./pages/admin/PendingStoresPage";
import { ChemistsPage } from "./pages/portal/ChemistsPage";
import { FamilyPage } from "./pages/portal/FamilyPage";
import { MedicinesPage } from "./pages/portal/MedicinesPage";
import { RequestPage } from "./pages/portal/RequestPage";
import { RequestsPage } from "./pages/portal/RequestsPage";
import { QuestionPage } from "./pages/store/QuestionPage";
import { InsightsPage } from "./pages/store/InsightsPage";
import { QueuePage } from "./pages/store/QueuePage";
import { StockPage } from "./pages/store/StockPage";
import { ReservationsPage } from "./pages/store/ReservationsPage";
import { StoreLayout } from "./pages/store/StoreLayout";
import { StoreProfilePage } from "./pages/store/StoreProfilePage";
import { StoreRegisterPage } from "./pages/store/StoreRegisterPage";
import { RegisterDoctorPage } from "./pages/RegisterDoctorPage";
import { RegisterPage } from "./pages/RegisterPage";
import { OverviewPage } from "./pages/portal/OverviewPage";
import { PortalLayout } from "./pages/portal/PortalLayout";
import { PrescriptionsPage } from "./pages/portal/PrescriptionsPage";
import { ProfilePage } from "./pages/portal/ProfilePage";
import { VisitsPage } from "./pages/portal/VisitsPage";

const HOME_LABEL: Record<Role, string> = {
  PATIENT: "My portal",
  DOCTOR: "My workspace",
  CHEMIST: "My store",
  ADMIN: "Verification",
};

export function App() {
  const { session, logout } = useAuth();
  const navigate = useNavigate();
  // The landing and sign-in pages carry their own navigation.
  const { pathname } = useLocation();
  const ownChrome = pathname === "/" || pathname.startsWith("/login");
  // Those pages hold their own <main>; landmarks must not nest.
  const Shell = ownChrome ? "div" : "main";

  function signOut() {
    logout();
    navigate("/", { replace: true });
  }

  return (
    <div className="app">
      {!ownChrome && (
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
                  <NavLink to={homeFor(session.role)}>{HOME_LABEL[session.role]}</NavLink>
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
      )}

      <Shell>
        <Routes>
          {/* Full-bleed pages manage their own width. */}
          <Route path="/" element={<LandingPage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/login/doctor" element={<LoginPage role="doctor" />} />
          <Route path="/login/chemist" element={<LoginPage role="chemist" />} />

          <Route element={<ProtectedRoute />}>
            <Route path="/portal" element={<PortalLayout />}>
              <Route index element={<OverviewPage />} />
              <Route path="visits" element={<VisitsPage />} />
              <Route path="prescriptions" element={<PrescriptionsPage />} />
              <Route path="profile" element={<ProfilePage />} />
              <Route path="family" element={<FamilyPage />} />
              <Route path="chemists" element={<ChemistsPage />} />
              <Route path="medicines" element={<MedicinesPage />} />
              <Route path="requests" element={<RequestsPage />} />
              <Route path="requests/:requestId" element={<RequestPage />} />
            </Route>
            <Route path="/doctor" element={<DoctorLayout />}>
              <Route index element={<SchedulePage />} />
              <Route path="visits/:visitId" element={<VisitPage />} />
              <Route path="patients/:patientId" element={<PatientHistoryPage />} />
              <Route path="hours" element={<HoursPage />} />
            </Route>
            <Route path="/store" element={<StoreLayout />}>
              <Route index element={<StoreProfilePage />} />
              <Route path="requests" element={<QueuePage />} />
              <Route path="requests/:requestId" element={<QuestionPage />} />
              <Route path="reservations" element={<ReservationsPage />} />
              <Route path="insights" element={<InsightsPage />} />
              <Route path="stock" element={<StockPage />} />
            </Route>
          </Route>

          {/* Everything else sits in a centred column. */}
          <Route element={<Contained />}>
            <Route path="/register" element={<RegisterPage />} />
            <Route path="/register/store" element={<StoreRegisterPage />} />
            <Route path="/register/doctor" element={<RegisterDoctorPage />} />
            <Route element={<ProtectedRoute />}>
              {/* Signed-in only: whoever picked a doctor on the landing page signs in first, then sees the list. */}
              <Route path="/doctors" element={<DoctorsPage />} />
              <Route path="/admin/stores" element={<PendingStoresPage />} />
              <Route path="/admin/doctors" element={<PendingDoctorsPage />} />
              <Route path="/doctors/:doctorId/book" element={<BookingPage />} />
              <Route path="/notifications" element={<NotificationsPage />} />
            </Route>
            {/* The old URL, kept working for existing bookmarks. */}
            <Route path="/appointments" element={<Navigate to="/portal/visits" replace />} />
            <Route path="*" element={<p className="muted">Page not found.</p>} />
          </Route>
        </Routes>
      </Shell>
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
