import { useMutation, useQueryClient } from "@tanstack/react-query";
import { queue } from "../../api/endpoints";
import type { QueueToken } from "../../api/types";

/** Asked again this often while a token is waiting: places move as the desk calls people in. */
export const QUEUE_POLL_MS = 10_000;

/** One walk-in token: the number, the place in line, and what to do. */
export function TokenCard({ token }: { token: QueueToken }) {
  const queryClient = useQueryClient();
  const leave = useMutation({
    mutationFn: () => queue.leave(token.id),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ["queue"] }),
  });
  const called = token.status === "CALLED";

  return (
    <article className={called ? "card token token--called" : "card token"} aria-live="polite">
      <p className="token__label">Token</p>
      <p className="token__number">#{token.tokenNo}</p>
      <p className="token__who">
        {token.patientName} · {token.doctorName}, {token.specialization}
      </p>
      {called ? (
        <p className="token__status">
          <strong>It's your turn. Please go in now.</strong>
        </p>
      ) : (
        <p className="token__status">
          {token.ahead === 0 ? "You are next" : `${token.ahead} ahead of you`}
          {" · "}about {token.estimatedWaitMinutes} min
        </p>
      )}
      {token.status === "WAITING" && (
        <button
          type="button"
          className="link link--danger"
          disabled={leave.isPending}
          onClick={() => {
            if (window.confirm(`Give up token #${token.tokenNo}?`)) leave.mutate();
          }}
        >
          Leave the queue
        </button>
      )}
    </article>
  );
}
