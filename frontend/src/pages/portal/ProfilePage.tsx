import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../../api/client";
import { portal } from "../../api/endpoints";
import { HealthForm } from "../../components/HealthForm";
import { ageFrom, formatDate, initials } from "../../lib/format";

const GENDER_LABEL = {
  MALE: "Male",
  FEMALE: "Female",
  OTHER: "Other",
  UNDISCLOSED: "Prefer not to say",
} as const;

export function ProfilePage() {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState(false);
  const profile = useQuery({ queryKey: ["portal", "profile"], queryFn: portal.profile });
  const save = useMutation({
    mutationFn: portal.updateProfile,
    onSuccess: (saved) => {
      queryClient.setQueryData(["portal", "profile"], saved);
      setEditing(false);
    },
  });

  if (profile.isPending) return <div className="card skeleton" style={{ height: 240 }} />;
  if (profile.isError) return <p className="error">Could not load your profile.</p>;

  const p = profile.data;
  const notSet = <span className="muted">Not provided</span>;

  return (
    <div className="stack">
      <header className="profile__head">
        <div className="avatar avatar--lg" aria-hidden="true">
          {initials(p.fullName)}
        </div>
        <div>
          <h1 className="portal__title">{p.fullName}</h1>
          <p className="muted">Patient since {formatDate(p.memberSince)}</p>
        </div>
        {!editing && (
          <button type="button" className="button--quiet profile__edit" onClick={() => setEditing(true)}>
            Edit health and location
          </button>
        )}
      </header>

      {editing ? (
        <section className="card">
          <h2 className="portal__subtitle">Health and location</h2>
          <HealthForm
            profile={p}
            submitLabel="Save"
            saving={save.isPending}
            error={save.isError ? (save.error instanceof ApiError ? save.error.message : "Could not save your details.") : null}
            fieldErrors={save.error instanceof ApiError ? save.error.fieldErrors : {}}
            onSave={(update) => save.mutate(update)}
            onSkip={() => setEditing(false)}
          />
        </section>
      ) : (
        <>
      <section className="card">
        <h2 className="portal__subtitle">Personal details</h2>
        <dl className="details">
          <dt>Date of birth</dt>
          <dd>
            {formatDate(p.dateOfBirth)} <span className="muted">({ageFrom(p.dateOfBirth)} years)</span>
          </dd>
          <dt>Gender</dt>
          <dd>{GENDER_LABEL[p.gender]}</dd>
          <dt>Blood group</dt>
          <dd>{p.bloodGroup ?? notSet}</dd>
          <dt>Height and weight</dt>
          <dd>
            {p.heightCm || p.weightKg
              ? [p.heightCm && `${p.heightCm} cm`, p.weightKg && `${p.weightKg} kg`].filter(Boolean).join(" · ")
              : notSet}
          </dd>
        </dl>
      </section>

      <section className="card">
        <h2 className="portal__subtitle">Medical history</h2>
        <dl className="details">
          <dt>Allergies</dt>
          <dd>{p.allergies ?? notSet}</dd>
          <dt>Long-term conditions</dt>
          <dd>{p.chronicConditions ?? notSet}</dd>
          <dt>Medicines now</dt>
          <dd>{p.currentMedications ?? notSet}</dd>
        </dl>
      </section>

      <section className="card">
        <h2 className="portal__subtitle">Contact</h2>
        <dl className="details">
          <dt>Email</dt>
          <dd>{p.email}</dd>
          <dt>Phone</dt>
          <dd>{p.phone ?? notSet}</dd>
          <dt>Address</dt>
          <dd>{[p.addressLine, p.city].filter(Boolean).join(", ") || notSet}</dd>
          <dt>Distances measured from</dt>
          <dd>{p.homeLatitude != null ? "Your saved location" : notSet}</dd>
          <dt>Emergency contact</dt>
          <dd>{p.emergencyContact ?? notSet}</dd>
        </dl>
      </section>
        </>
      )}
    </div>
  );
}
