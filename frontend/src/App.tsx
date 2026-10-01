import { useQuery } from "@tanstack/react-query";
import { Suspense } from "react";
import { Link, Navigate, NavLink, Outlet, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { notifications } from "./api/endpoints";
import type { Role } from "./api/types";
import { homeFor, useAuth } from "./auth/context";
import { PageLoading } from "./components/PageLoading";
import { ProtectedRoute } from "./components/ProtectedRoute";
import { DoctorLayout } from "./pages/doctor/DoctorLayout";
import { LandingPage } from "./pages/LandingPage";
import { LoginPage } from "./pages/LoginPage";
import { NOTIFICATIONS_KEY } from "./lib/notifications";
import { StoreLayout } from "./pages/store/StoreLayout";
import { PortalLayout } from "./pages/portal/PortalLayout";
import { lazyPage } from "./lib/lazyPage";

// Every page but the landing and sign-in pages is loaded when first opened,
// so a visitor downloads the patient portal, not the doctor, chemist and
// admin workspaces too. Layouts stay in the main bundle: they hold the
// <Suspense> each page loads inside, so the navigation never blinks.
const BookingPage = lazyPage(() => import("./pages/BookingPage"), "BookingPage");
const HoursPage = lazyPage(() => import("./pages/doctor/HoursPage"), "HoursPage");
const PracticePage = lazyPage(() => import("./pages/doctor/PracticePage"), "PracticePage");
const PatientHistoryPage = lazyPage(() => import("./pages/doctor/PatientHistoryPage"), "PatientHistoryPage");
const SchedulePage = lazyPage(() => import("./pages/doctor/SchedulePage"), "SchedulePage");
const VisitPage = lazyPage(() => import("./pages/doctor/VisitPage"), "VisitPage");
const DoctorsPage = lazyPage(() => import("./pages/DoctorsPage"), "DoctorsPage");
const NotificationsPage = lazyPage(() => import("./pages/NotificationsPage"), "NotificationsPage");
const PendingDoctorsPage = lazyPage(() => import("./pages/admin/PendingDoctorsPage"), "PendingDoctorsPage");
const PendingStoresPage = lazyPage(() => import("./pages/admin/PendingStoresPage"), "PendingStoresPage");
const ChemistsPage = lazyPage(() => import("./pages/portal/ChemistsPage"), "ChemistsPage");
const FamilyPage = lazyPage(() => import("./pages/portal/FamilyPage"), "FamilyPage");
const MedicinesPage = lazyPage(() => import("./pages/portal/MedicinesPage"), "MedicinesPage");
const RequestPage = lazyPage(() => import("./pages/portal/RequestPage"), "RequestPage");
const RequestsPage = lazyPage(() => import("./pages/portal/RequestsPage"), "RequestsPage");
const QuestionPage = lazyPage(() => import("./pages/store/QuestionPage"), "QuestionPage");
const InsightsPage = lazyPage(() => import("./pages/store/InsightsPage"), "InsightsPage");
const QueuePage = lazyPage(() => import("./pages/store/QueuePage"), "QueuePage");
const StockPage = lazyPage(() => import("./pages/store/StockPage"), "StockPage");
const ReservationsPage = lazyPage(() => import("./pages/store/ReservationsPage"), "ReservationsPage");
const StoreProfilePage = lazyPage(() => import("./pages/store/StoreProfilePage"), "StoreProfilePage");
const StoreRegisterPage = lazyPage(() => import("./pages/store/StoreRegisterPage"), "StoreRegisterPage");
const RegisterDoctorPage = lazyPage(() => import("./pages/RegisterDoctorPage"), "RegisterDoctorPage");
const Lab3D = lazyPage(() => import("./pages/Lab3D"), "Lab3D");
const WelcomePage = lazyPage(() => import("./pages/portal/WelcomePage"), "WelcomePage");
const RegisterPage = lazyPage(() => import("./pages/RegisterPage"), "RegisterPage");
const OverviewPage = lazyPage(() => import("./pages/portal/OverviewPage"), "OverviewPage");
const PrescriptionsPage = lazyPage(() => import("./pages/portal/PrescriptionsPage"), "PrescriptionsPage");
const ProfilePage = lazyPage(() => import("./pages/portal/ProfilePage"), "ProfilePage");
const VisitsPage = lazyPage(() => import("./pages/portal/VisitsPage"), "VisitsPage");
const TokensPage = lazyPage(() => import("./pages/portal/TokensPage"), "TokensPage");
const WalkInPage = lazyPage(() => import("./pages/queue/WalkInPage"), "WalkInPage");
const FrontDeskPage = lazyPage(() => import("./pages/doctor/FrontDeskPage"), "FrontDeskPage");
const VideoRoomPage = lazyPage(() => import("./pages/VideoRoomPage"), "VideoRoomPage");

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
  const ownChrome = pathname === "/" || pathname.startsWith("/login") || pathname.startsWith("/lab/");
  // The three workspaces bring their own rail; the top bar is for the pages
  // in between (the directory, booking, notifications, sign-up).
  const inWorkspace = /^\/(portal|doctor|store)(\/|$)/.test(pathname);
  // One line colour per role: the role repaints every accent below the root.
  const role = session?.role === "DOCTOR" ? "doctor" : session?.role === "CHEMIST" ? "chemist" : "patient";
  // Those pages hold their own <main>; landmarks must not nest.
  const Shell = ownChrome ? "div" : "main";

  function signOut() {
    // Leave first, so a protected page does not remember itself as the place to return to.
    navigate("/", { replace: true });
    logout();
  }

  return (
    <div className={ownChrome ? "app" : "app in"} data-role={role}>
      {!ownChrome && !inWorkspace && (
        <header className="topbar">
          <div className="topbar__inner">
            <Link to="/" className="brand">
              <span className="brand__mark" aria-hidden="true" />
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
          {/* A preview of the 3D direction. Not linked from anywhere, and not indexed. */}
          <Route path="/lab/3d" element={<Suspense fallback={<PageLoading />}><Lab3D /></Suspense>} />
          <Route path="/login/doctor" element={<LoginPage role="doctor" />} />
          <Route path="/login/chemist" element={<LoginPage role="chemist" />} />

          <Route element={<ProtectedRoute />}>
            <Route path="/portal" element={<PortalLayout />}>
              <Route index element={<OverviewPage />} />
              <Route path="visits" element={<VisitsPage />} />
              <Route path="queue" element={<TokensPage />} />
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
              <Route path="practice" element={<PracticePage />} />
              <Route path="queue" element={<FrontDeskPage />} />
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
              <Route path="/doctors/:doctorId/walk-in" element={<WalkInPage />} />
              <Route path="/visits/:appointmentId/video" element={<VideoRoomPage />} />
              <Route path="/notifications" element={<NotificationsPage />} />
              <Route path="/welcome" element={<WelcomePage />} />
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
      <Suspense fallback={<PageLoading />}>
        <Outlet />
      </Suspense>
    </div>
  );
}
