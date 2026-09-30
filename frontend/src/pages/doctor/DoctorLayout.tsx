import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { WorkspaceShell } from "../../components/WorkspaceShell";
import { doctorAccount } from "../../api/endpoints";
import { useAuth } from "../../auth/context";
import { initials } from "../../lib/format";

const SECTIONS = [
  { to: "/doctor", label: "Schedule", end: true },
  { to: "/doctor/queue", label: "Front desk" },
  { to: "/doctor/hours", label: "Your hours" },
  { to: "/doctor/practice", label: "Fees and insurance" },
];

export function DoctorLayout() {
  const { session } = useAuth();
  const profile = useQuery({
    queryKey: ["doctor", "profile"],
    queryFn: doctorAccount.profile,
    enabled: session?.role === "DOCTOR",
    staleTime: 60_000,
  });

  // The API refuses non-doctors anyway; this only explains why the page is empty.
  if (session && session.role !== "DOCTOR") {
    return (
      <div className="content">
        <div className="card empty">
          <h1>The workspace is for doctors</h1>
          <p className="muted">You are signed in as {session.role.toLowerCase()}.</p>
          <Link to="/">Go to the home page</Link>
        </div>
      </div>
    );
  }

  return (
    <WorkspaceShell
      label="Doctor workspace"
      who={
        session && (
          <div className="ws__who">
            <div className="avatar avatar--sm" aria-hidden="true">
              {initials(session.fullName)}
            </div>
            <div>
              <strong>{session.fullName}</strong>
              <span className="muted">Doctor</span>
            </div>
          </div>
        )
      }
      stations={SECTIONS}
      banner={
        profile.data &&
        !profile.data.verified && (
          <p className="notice" role="status">
            We are checking registration number <strong>{profile.data.registrationNumber}</strong> with the{" "}
            {profile.data.medicalCouncil}. Until then patients cannot find or book you; set your hours now and they
            open the moment you are verified.
          </p>
        )
      }
    />
  );
}
