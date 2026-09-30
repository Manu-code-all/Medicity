import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useLocation, useNavigate } from "react-router-dom";
import { ApiError } from "../../api/client";
import { portal } from "../../api/endpoints";
import { HealthForm } from "../../components/HealthForm";
import { firstName } from "../../lib/format";

/**
 * The step after creating an account: the questions a clinic asks before it
 * sees someone, and where the person is. Every part can be skipped, and the
 * same form is on the profile page for later.
 */
export function WelcomePage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const returnTo = (useLocation().state as { from?: string } | null)?.from ?? "/portal";
  const profile = useQuery({ queryKey: ["portal", "profile"], queryFn: portal.profile });

  const save = useMutation({
    mutationFn: portal.updateProfile,
    onSuccess: (saved) => {
      queryClient.setQueryData(["portal", "profile"], saved);
      navigate(returnTo, { replace: true });
    },
  });

  if (profile.isPending) return <div className="card skeleton" style={{ height: 320 }} />;
  if (profile.isError) return <p className="error">Could not load your profile.</p>;

  return (
    <div className="welcome">
      <header>
        <p className="eyebrow">Your account is ready</p>
        <h1>Welcome, {firstName(profile.data.fullName)}</h1>
        <p className="muted">
          A few details help a doctor prepare before you arrive, and let us show how far clinics and chemists are from
          you. Every question is optional.
        </p>
      </header>
      <HealthForm
        profile={profile.data}
        submitLabel="Save and continue"
        saving={save.isPending}
        error={save.isError ? (save.error instanceof ApiError ? save.error.message : "Could not save your details.") : null}
        fieldErrors={save.error instanceof ApiError ? save.error.fieldErrors : {}}
        onSave={(update) => save.mutate(update)}
        onSkip={() => navigate(returnTo, { replace: true })}
      />
    </div>
  );
}
