import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useLocation } from "react-router-dom";
import { privacy } from "../api/endpoints";
import type { ConsentType } from "../api/types";
import { useAuth } from "../auth/context";

const CONSENTS_KEY = ["consents"] as const;

const LEGAL_PATHS = ["/privacy", "/terms", "/disclaimer", "/delete-account"];

const TEXT: Record<ConsentType, { to: string; label: string; says: string }> = {
  TERMS_OF_SERVICE: {
    to: "/terms",
    label: "Terms of service",
    says: "I agree to the updated terms of service.",
  },
  PRIVACY_NOTICE: {
    to: "/privacy",
    label: "Privacy notice",
    says: "I have read the updated privacy notice.",
  },
  HEALTH_DATA_PROCESSING: {
    to: "/privacy",
    label: "Health information",
    says: "I agree to Medicity keeping and using my health information, and my family members' that I manage, as the privacy notice describes.",
  },
};

/**
 * Asks a signed-in person to agree again when a text they agreed to has a new
 * version (or when they never agreed: accounts made before consent was
 * recorded). Shown over everything until they agree or sign out; each text is
 * its own tick box, never one for all.
 *
 * The API owns what is current and who is behind; this only shows it. A
 * network failure shows nothing rather than blocking the app.
 */
export function ConsentGate() {
  const { session, logout } = useAuth();
  const { pathname } = useLocation();
  const queryClient = useQueryClient();
  const [ticked, setTicked] = useState<ConsentType[]>([]);

  const status = useQuery({
    queryKey: CONSENTS_KEY,
    queryFn: privacy.consents,
    enabled: session !== null,
    staleTime: 5 * 60_000,
  });
  const accept = useMutation({
    mutationFn: (types: ConsentType[]) => privacy.accept(types),
    onSuccess: (next) => queryClient.setQueryData(CONSENTS_KEY, next),
  });

  // Never over the texts themselves: the links above open them in a new tab.
  if (!session || !status.data?.pending || LEGAL_PATHS.includes(pathname)) return null;
  const outstanding = status.data.items.filter((i) => !i.upToDate);
  const allTicked = outstanding.every((i) => ticked.includes(i.type));

  function toggle(type: ConsentType, on: boolean) {
    setTicked((prev) => (on ? [...prev, type] : prev.filter((t) => t !== type)));
  }

  return (
    <div className="consent-gate" role="dialog" aria-modal="true" aria-labelledby="consent-title">
      <form
        className="consent-gate__panel"
        onSubmit={(e) => {
          e.preventDefault();
          accept.mutate(outstanding.map((i) => i.type));
        }}
      >
        <h2 id="consent-title">Please review what you agree to</h2>
        <p className="muted">
          Our terms or privacy notice have changed since you last agreed (or you have not yet). Read them, then agree
          to continue. You can instead sign out, or{" "}
          <Link to="/delete-account" target="_blank" rel="noreferrer">
            delete your account
          </Link>
          .
        </p>
        {outstanding.map((item) => {
          const text = TEXT[item.type];
          return (
            <label key={item.type} className="check">
              <input
                type="checkbox"
                checked={ticked.includes(item.type)}
                onChange={(e) => toggle(item.type, e.target.checked)}
              />
              <span>
                {text.says}{" "}
                <Link to={text.to} target="_blank" rel="noreferrer">
                  Read the {text.label.toLowerCase()}
                </Link>
                .
              </span>
            </label>
          );
        })}
        {accept.isError && (
          <p className="error" role="alert">
            Could not save your choice. Try again.
          </p>
        )}
        <div className="rx-form__actions">
          <button type="submit" disabled={!allTicked || accept.isPending}>
            {accept.isPending ? "Saving…" : "I agree"}
          </button>
          <button type="button" className="link" onClick={logout}>
            Sign out
          </button>
        </div>
      </form>
    </div>
  );
}
