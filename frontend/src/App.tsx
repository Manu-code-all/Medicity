import { Link, Navigate, NavLink, Outlet, Route, Routes, useNavigate } from "react-router-dom";
import { useAuth } from "./auth/context";
import { ProtectedRoute } from "./components/ProtectedRoute";
import { BookingPage } from "./pages/BookingPage";
import { DoctorsPage } from "./pages/DoctorsPage";
import { LandingPage } from "./pages/LandingPage";
import { LoginPage } from "./pages/LoginPage";
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
                <NavLink to="/portal">My portal</NavLink>
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
          </Route>

          {/* Everything else sits in a centred column. */}
          <Route element={<Contained />}>
            <Route path="/doctors" element={<DoctorsPage />} />
            <Route path="/login" element={<LoginPage />} />
            <Route path="/register" element={<RegisterPage />} />
            <Route element={<ProtectedRoute />}>
              <Route path="/doctors/:doctorId/book" element={<BookingPage />} />
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

function Contained() {
  return (
    <div className="content">
      <Outlet />
    </div>
  );
}
