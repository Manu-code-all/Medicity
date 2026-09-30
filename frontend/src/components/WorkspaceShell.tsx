import { Suspense, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link, NavLink, Outlet, useNavigate } from "react-router-dom";
import { Bell, SignOut } from "@phosphor-icons/react";
import { notifications } from "../api/endpoints";
import { useAuth } from "../auth/context";
import { NOTIFICATIONS_KEY } from "../lib/notifications";
import { PageLoading } from "./PageLoading";

export interface Station {
  to: string;
  label: string;
  /** Match the path exactly (the section that sits at the workspace's root). */
  end?: boolean;
}

/**
 * The frame of a signed-in workspace: a rail with the brand, who is signed
 * in, the sections as stations on the role's line, and the account actions.
 * The page loads inside, so the rail never blinks while a page's code arrives.
 */
export function WorkspaceShell({
  label,
  who,
  stations,
  cta,
  banner,
}: {
  /** Names the navigation for assistive technology: "Patient portal". */
  label: string;
  who: ReactNode;
  stations: Station[];
  cta?: ReactNode;
  banner?: ReactNode;
}) {
  return (
    <div className="ws">
      <aside className="ws__rail">
        <Link to="/" className="ws__brand">
          <span className="ws__mark" aria-hidden="true" />
          Medicity
        </Link>
        <div className="ws__whoSlot">{who}</div>
        <nav aria-label={label} className="ws__line">
          {stations.map((s) => (
            <NavLink key={s.to} to={s.to} end={s.end ?? false} className={({ isActive }) => (isActive ? "ws__stop is-active" : "ws__stop")}>
              {s.label}
            </NavLink>
          ))}
        </nav>
        {cta}
        <div className="ws__foot">
          <NotificationsLink />
          <SignOutButton />
        </div>
      </aside>
      <div className="ws__main">
        {banner}
        <Suspense fallback={<PageLoading />}>
          <Outlet />
        </Suspense>
      </div>
    </div>
  );
}

/**
 * Link to the notifications with the unread count. Polled once a minute:
 * notifications are delivered by a background relay a moment after the
 * change, so a push channel would add infrastructure for no visible gain at
 * this scale.
 */
function NotificationsLink() {
  const list = useQuery({ queryKey: NOTIFICATIONS_KEY, queryFn: notifications.mine, refetchInterval: 60_000 });
  const unread = list.data?.unread ?? 0;
  return (
    <NavLink to="/notifications" className="ws__action" aria-label={unread ? `Notifications, ${unread} unread` : "Notifications"}>
      <Bell size={20} aria-hidden="true" />
      <span className="ws__actionLabel">Notifications</span>
      {unread > 0 && <span className="ws__count">{unread}</span>}
    </NavLink>
  );
}

function SignOutButton() {
  const { logout } = useAuth();
  const navigate = useNavigate();
  return (
    <button
      type="button"
      className="ws__action"
      onClick={() => {
        // Leave first: signing out from inside a protected page would otherwise
        // send the next sign-in straight back to that page.
        navigate("/", { replace: true });
        logout();
      }}
    >
      <SignOut size={20} aria-hidden="true" />
      <span className="ws__actionLabel">Sign out</span>
    </button>
  );
}
