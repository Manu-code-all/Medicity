import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { Link, useNavigate } from "react-router-dom";
import { ApiError } from "../api/client";
import { privacy } from "../api/endpoints";
import { useAuth } from "../auth/context";
import { saveJson } from "../lib/download";

/**
 * A patient's data rights, on the profile page: a copy of everything held
 * about the account and the family it manages, and deleting the account.
 * Both act on the whole account, whichever family member is being viewed.
 */
export function PrivacyPanel() {
  const { logout } = useAuth();
  const navigate = useNavigate();
  const [confirming, setConfirming] = useState(false);
  const [password, setPassword] = useState("");

  const download = useMutation({
    mutationFn: privacy.exportData,
    onSuccess: (data) => saveJson(`medicity-my-data-${new Date().toISOString().slice(0, 10)}.json`, data),
  });

  const remove = useMutation({
    mutationFn: () => privacy.deleteAccount(password),
    onSuccess: () => {
      // The server has ended every session; this clears the browser's copy too.
      logout();
      navigate("/delete-account?done=1", { replace: true });
    },
  });

  return (
    <section className="card" aria-labelledby="your-data">
      <h2 className="portal__subtitle" id="your-data">
        Your data
      </h2>
      <p className="muted">
        What Medicity holds about you and the family members you manage, and your choices. See the{" "}
        <Link to="/privacy">privacy notice</Link>.
      </p>

      <div className="privacy-actions">
        <button type="button" className="button--ghost" disabled={download.isPending} onClick={() => download.mutate()}>
          {download.isPending ? "Preparing…" : "Download my data"}
        </button>
        <p className="muted">A file with your profile, family, visits, prescriptions and more, in JSON.</p>
        {download.isError && (
          <p className="error" role="alert">
            Could not prepare your data. Try again in a moment.
          </p>
        )}
      </div>

      {!confirming ? (
        <div className="privacy-actions">
          <button type="button" className="button--danger" onClick={() => setConfirming(true)}>
            Delete my account
          </button>
        </div>
      ) : (
        <form
          className="danger-zone"
          onSubmit={(e) => {
            e.preventDefault();
            remove.mutate();
          }}
        >
          <h3>Delete your account for good?</h3>
          <div className="danger-zone__warning" role="note">
            <p>
              <strong>This cannot be undone.</strong> You will be signed out everywhere and will not be able to sign in
              to this account again.
            </p>
            <ul>
              <li>Your name, contact details and health details are erased, and so are your family members'.</li>
              <li>Your upcoming visits are cancelled, and any medicine held for you is released.</li>
              <li>
                Records your doctors must keep by law (past visits and prescriptions) stay, without your name.{" "}
                <Link to="/delete-account">What is kept and why</Link>.
              </li>
            </ul>
            <p>Want a copy first? Download your data above before you continue.</p>
          </div>
          <label htmlFor="delete-password">Type your password to confirm</label>
          <input
            id="delete-password"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          {remove.isError && (
            <p className="error" role="alert">
              {remove.error instanceof ApiError ? remove.error.message : "Could not delete your account. Try again."}
            </p>
          )}
          <div className="rx-form__actions">
            <button type="submit" className="button--danger" disabled={remove.isPending || password === ""}>
              {remove.isPending ? "Deleting…" : "Delete my account permanently"}
            </button>
            <button
              type="button"
              className="link"
              onClick={() => {
                setConfirming(false);
                setPassword("");
                remove.reset();
              }}
            >
              Keep my account
            </button>
          </div>
        </form>
      )}
    </section>
  );
}
