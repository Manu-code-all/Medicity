import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { queue } from "../../api/endpoints";
import { QUEUE_POLL_MS, TokenCard } from "../queue/TokenCard";

/** Today's walk-in tokens for the account holder and their family, kept up to date. */
export function TokensPage() {
  const mine = useQuery({ queryKey: ["queue", "mine"], queryFn: queue.mine, refetchInterval: QUEUE_POLL_MS });

  return (
    <div className="stack">
      <header>
        <h1 className="portal__title">Walk-in tokens</h1>
        <p className="muted">Your place in today's lines. This page updates by itself.</p>
      </header>
      {mine.isPending && <div className="card skeleton" style={{ height: 160 }} />}
      {mine.isError && <p className="error">Could not load your tokens.</p>}
      {mine.data?.length === 0 && (
        <div className="card empty">
          <h2>No tokens today</h2>
          <p className="muted">
            To see a doctor today without an appointment, pick one in the <Link to="/doctors">directory</Link> and
            choose "Walk in today".
          </p>
        </div>
      )}
      {mine.data?.map((t) => (
        <TokenCard key={t.id} token={t} />
      ))}
    </div>
  );
}
