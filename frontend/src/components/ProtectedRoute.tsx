import { Navigate, Outlet, useLocation } from "react-router-dom";
import { useAuth } from "../auth/context";

/**
 * Gates routes that need a signed-in user.
 *
 * This is convenience, not security. Every protected endpoint enforces
 * authorization server-side; removing this component would expose empty
 * screens, not data.
 */
export function ProtectedRoute() {
  const { session } = useAuth();
  const location = useLocation();

  if (!session) {
    // Remember where they were headed so sign-in can return them there.
    // Send doctors and chemists to their own sign-in page.
    const signIn = location.pathname.startsWith("/doctor/") || location.pathname === "/doctor"
      ? "/login/doctor"
      : location.pathname.startsWith("/store")
        ? "/login/chemist"
        : "/login";
    return <Navigate to={signIn} replace state={{ from: location.pathname }} />;
  }
  return <Outlet />;
}
