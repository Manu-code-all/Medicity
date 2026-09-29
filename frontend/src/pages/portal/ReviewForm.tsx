import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../../api/client";
import { reviews } from "../../api/endpoints";

const WORDS = ["", "Poor", "Fair", "Good", "Very good", "Excellent"];

/** Stars and a few words about a visit that took place; sent once. */
export function ReviewForm({ visitId, doctorName }: { visitId: string; doctorName: string }) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [rating, setRating] = useState(0);
  const [comment, setComment] = useState("");

  const send = useMutation({
    mutationFn: () => reviews.submit(visitId, rating, comment),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ["portal"] }),
  });

  if (send.isSuccess) return <p className="muted small">Thank you. Your review helps others choose.</p>;
  if (!open) {
    return (
      <button type="button" className="link" onClick={() => setOpen(true)}>
        Rate this visit
      </button>
    );
  }

  return (
    <form
      className="review-form"
      onSubmit={(e) => {
        e.preventDefault();
        if (rating > 0) send.mutate();
      }}
    >
      <fieldset className="stars">
        <legend>How was your visit with {doctorName}?</legend>
        {[1, 2, 3, 4, 5].map((n) => (
          <label key={n} className={n <= rating ? "star star--on" : "star"}>
            <input
              type="radio"
              name={`rating-${visitId}`}
              value={n}
              checked={rating === n}
              onChange={() => setRating(n)}
              aria-label={`${n} star${n > 1 ? "s" : ""}, ${WORDS[n]}`}
            />
            <span aria-hidden="true">★</span>
          </label>
        ))}
        <span className="stars__word" aria-hidden="true">{WORDS[rating]}</span>
      </fieldset>
      <label htmlFor={`review-${visitId}`} className="sr-only">
        A few words (optional)
      </label>
      <textarea
        id={`review-${visitId}`}
        rows={2}
        maxLength={1000}
        placeholder="A few words for other patients (optional)"
        value={comment}
        onChange={(e) => setComment(e.target.value)}
      />
      {send.isError && (
        <p className="error" role="alert">
          {send.error instanceof ApiError ? send.error.message : "Could not send the review."}
        </p>
      )}
      <div className="review-form__actions">
        <button type="submit" className="button button--sm" disabled={rating === 0 || send.isPending}>
          {send.isPending ? "Sending…" : "Send review"}
        </button>
        <button type="button" className="link" onClick={() => setOpen(false)}>
          Not now
        </button>
      </div>
    </form>
  );
}
