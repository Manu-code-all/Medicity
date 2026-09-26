import { useQuery } from "@tanstack/react-query";
import { portal } from "../../api/endpoints";
import { ageFrom, formatDate, initials } from "../../lib/format";

const GENDER_LABEL = {
  MALE: "Male",
  FEMALE: "Female",
  OTHER: "Other",
  UNDISCLOSED: "Prefer not to say",
} as const;

export function ProfilePage() {
  const profile = useQuery({ queryKey: ["portal", "profile"], queryFn: portal.profile });

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
      </header>

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
          <dt>Emergency contact</dt>
          <dd>{p.emergencyContact ?? notSet}</dd>
        </dl>
      </section>
    </div>
  );
}
