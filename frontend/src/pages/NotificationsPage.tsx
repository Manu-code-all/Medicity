import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { notifications } from "../api/endpoints";
import type { AppNotification } from "../api/types";
import { formatDate, formatDayLong, formatTime } from "../lib/format";

export const NOTIFICATIONS_KEY = ["notifications"] as const;

export function NotificationsPage() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const list = useQuery({ queryKey: NOTIFICATIONS_KEY, queryFn: notifications.mine });

  const refresh = () => void queryClient.invalidateQueries({ queryKey: NOTIFICATIONS_KEY });
  const markRead = useMutation({ mutationFn: notifications.markRead, onSuccess: refresh });
  const markAll = useMutation({ mutationFn: notifications.markAllRead, onSuccess: refresh });

  function open(n: AppNotification) {
    if (!n.read) markRead.mutate(n.id);
    if (n.link) navigate(n.link);
  }

  if (list.isPending) return <div className="card skeleton" style={{ height: 160 }} />;
  if (list.isError) return <p className="error">Could not load notifications.</p>;

  const { items, unread } = list.data;

  return (
    <div className="stack">
      <header className="section__head">
        <h1 className="portal__title">Notifications</h1>
        {unread > 0 && (
          <button type="button" className="link" onClick={() => markAll.mutate()} disabled={markAll.isPending}>
            Mark all as read
          </button>
        )}
      </header>

      {items.length === 0 && (
        <div className="card empty">
          <h2>Nothing yet</h2>
          <p className="muted">Bookings, cancellations and prescriptions will show up here.</p>
        </div>
      )}

      <ul className="notification-list">
        {items.map((n) => (
          <li key={n.id}>
            <button
              type="button"
              className={n.read ? "card notification" : "card notification notification--unread"}
              onClick={() => open(n)}
            >
              <span className="notification__head">
                <strong>{n.title}</strong>
                <span className="muted small">{formatDate(n.createdAt)}</span>
              </span>
              <span>{n.body}</span>
              {n.occursAt && (
                <span className="muted small">
                  {formatDayLong(n.occursAt)} at {formatTime(n.occursAt)}
                </span>
              )}
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
