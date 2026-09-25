import { Link, Navigate, Route, Routes } from "react-router-dom";
import { useAuth } from "./auth/context";
import { ProtectedRoute } from "./components/ProtectedRoute";
import { AppointmentsPage } from "./pages/AppointmentsPage";
import { BookingPage } from "./pages/BookingPage";
import { DoctorsPage } from "./pages/DoctorsPage";
import { LoginPage } from "./pages/LoginPage";
import { RegisterPage } from "./pages/RegisterPage";

export function App() {
  const { session, logout } = useAuth();

  return (
    <div className="app">
      <header className="topbar">
        <Link to="/" className="brand">
          Medicity
        </Link>
        <nav>
          <Link to="/doctors">Find a doctor</Link>
          {session ? (
            <>
              <Link to="/appointments">My appointments</Link>
              <span className="muted">{session.fullName}</span>
              <button type="button" className="link" onClick={logout}>
                Sign out
              </button>
            </>
          ) : (
            <>
              <Link to="/login">Sign in</Link>
              <Link to="/register">Register</Link>
            </>
          )}
        </nav>
      </header>

      <main className="content">
        <Routes>
          <Route path="/" element={<Navigate to="/doctors" replace />} />
          <Route path="/doctors" element={<DoctorsPage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />

          <Route element={<ProtectedRoute />}>
            <Route path="/doctors/:doctorId/book" element={<BookingPage />} />
            <Route path="/appointments" element={<AppointmentsPage />} />
          </Route>

          <Route path="*" element={<p className="muted">Page not found.</p>} />
        </Routes>
      </main>
    </div>
  );
}
