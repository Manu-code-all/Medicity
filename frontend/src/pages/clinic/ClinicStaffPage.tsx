import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../../api/client";
import { clinicAdmin } from "../../api/endpoints";
import type { ClinicCredentials, ClinicStaffMember } from "../../api/types";

const STAFF_KEY = ["clinic", "staff"];

const ROLE_LABEL: Record<ClinicStaffMember["role"], string> = {
  CLINIC_ADMIN: "Administrator",
  RECEPTIONIST: "Receptionist",
  DOCTOR: "Doctor",
};

/** Everyone at the clinic, and the forms to add a doctor or a receptionist. */
export function ClinicStaffPage() {
  const queryClient = useQueryClient();
  const staff = useQuery({ queryKey: STAFF_KEY, queryFn: clinicAdmin.staff });
  const [shown, setShown] = useState<ClinicCredentials | null>(null);
  const [adding, setAdding] = useState<"doctor" | "receptionist" | null>(null);

  const refresh = () => void queryClient.invalidateQueries({ queryKey: STAFF_KEY });
  const reset = useMutation({
    mutationFn: clinicAdmin.resetPassword,
    onSuccess: (c) => {
      setShown(c);
      refresh();
    },
  });
  const toggle = useMutation({
    mutationFn: ({ userId, enabled }: { userId: string; enabled: boolean }) => clinicAdmin.setEnabled(userId, enabled),
    onSuccess: refresh,
  });
  const error = [reset.error, toggle.error].find(Boolean);

  function created(c: ClinicCredentials) {
    setShown(c);
    setAdding(null);
    refresh();
  }

  return (
    <div className="stack">
      <header className="section__head">
        <div>
          <h1 className="portal__title">People</h1>
          <p className="muted">Doctors and front-desk staff at this clinic.</p>
        </div>
        <div className="rx-form__actions">
          <button type="button" onClick={() => setAdding("doctor")}>
            Add a doctor
          </button>
          <button type="button" className="button--quiet" onClick={() => setAdding("receptionist")}>
            Add a receptionist
          </button>
        </div>
      </header>

      {shown && (
        <div className="notice notice--ok" role="status">
          <p>
            <strong>{shown.fullName}</strong> ({shown.email}) can sign in with this temporary password. It is shown only
            now; they must choose their own at first sign-in.
          </p>
          <p>
            <code aria-label="Temporary password">{shown.temporaryPassword}</code>
          </p>
          <button type="button" className="link" onClick={() => setShown(null)}>
            I have noted it
          </button>
        </div>
      )}

      {error && (
        <p className="error" role="alert">
          {error instanceof ApiError ? error.message : "Something went wrong."}
        </p>
      )}

      {adding === "doctor" && <DoctorForm onDone={created} onCancel={() => setAdding(null)} />}
      {adding === "receptionist" && <ReceptionistForm onDone={created} onCancel={() => setAdding(null)} />}

      <section className="card" aria-labelledby="roster">
        <h2 id="roster">Roster</h2>
        {staff.isPending && <p className="muted">Loading…</p>}
        {staff.isError && <p className="error">Could not load the roster.</p>}
        {staff.data && (
          <div className="table-wrap">
            <table className="rx__table">
              <thead>
                <tr>
                  <th scope="col">Name</th>
                  <th scope="col">Role</th>
                  <th scope="col">Status</th>
                  <th scope="col">
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {staff.data.map((m) => (
                  <tr key={m.userId}>
                    <td>
                      {m.fullName}
                      <br />
                      <span className="muted small">{m.email}</span>
                    </td>
                    <td>
                      {ROLE_LABEL[m.role]}
                      {m.specialization && <span className="muted small"> · {m.specialization}</span>}
                    </td>
                    <td>{statusOf(m)}</td>
                    <td>
                      {m.role !== "CLINIC_ADMIN" && (
                        <div className="desk__actions">
                          <button
                            type="button"
                            className="link"
                            disabled={reset.isPending}
                            onClick={() => reset.mutate(m.userId)}
                            aria-label={`Reset password for ${m.fullName}`}
                          >
                            Reset password
                          </button>
                          <button
                            type="button"
                            className="link"
                            disabled={toggle.isPending}
                            onClick={() => toggle.mutate({ userId: m.userId, enabled: !m.enabled })}
                            aria-label={`${m.enabled ? "Disable" : "Enable"} ${m.fullName}`}
                          >
                            {m.enabled ? "Disable" : "Enable"}
                          </button>
                        </div>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}

function statusOf(m: ClinicStaffMember): string {
  if (!m.enabled) return "Disabled";
  if (m.role === "DOCTOR" && !m.verified) return "Waiting for Medicity to verify";
  if (m.mustChangePassword) return "Has not signed in yet";
  return "Active";
}

function ReceptionistForm({ onDone, onCancel }: { onDone: (c: ClinicCredentials) => void; onCancel: () => void }) {
  const [fullName, setFullName] = useState("");
  const [email, setEmail] = useState("");
  const [phone, setPhone] = useState("");
  const add = useMutation({
    mutationFn: () =>
      clinicAdmin.addReceptionist({ fullName: fullName.trim(), email: email.trim(), phone: phone.trim() || undefined }),
    onSuccess: onDone,
  });
  return (
    <form
      className="card rx-form"
      aria-label="Add a receptionist"
      onSubmit={(e) => {
        e.preventDefault();
        add.mutate();
      }}
    >
      <h2>Add a receptionist</h2>
      <label htmlFor="r-name">Full name</label>
      <input id="r-name" required maxLength={120} value={fullName} onChange={(e) => setFullName(e.target.value)} />
      <label htmlFor="r-email">Email</label>
      <input id="r-email" type="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
      <label htmlFor="r-phone">Phone (optional)</label>
      <input id="r-phone" inputMode="tel" value={phone} onChange={(e) => setPhone(e.target.value)} />
      <FormTail error={add.error} busy={add.isPending} submit="Add receptionist" onCancel={onCancel} />
    </form>
  );
}

function DoctorForm({ onDone, onCancel }: { onDone: (c: ClinicCredentials) => void; onCancel: () => void }) {
  const [f, setF] = useState({
    fullName: "",
    email: "",
    phone: "",
    specialization: "",
    medicalCouncil: "",
    registrationNumber: "",
    qualification: "",
    yearsExperience: "0",
    consultationFee: "500",
  });
  const set = (key: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [key]: e.target.value });
  const add = useMutation({
    mutationFn: () =>
      clinicAdmin.addDoctor({
        fullName: f.fullName.trim(),
        email: f.email.trim(),
        phone: f.phone.trim() || undefined,
        specialization: f.specialization.trim(),
        medicalCouncil: f.medicalCouncil.trim(),
        registrationNumber: f.registrationNumber.trim(),
        qualification: f.qualification.trim(),
        yearsExperience: Number(f.yearsExperience) || 0,
        consultationFee: Number(f.consultationFee) || 0,
      }),
    onSuccess: onDone,
  });
  return (
    <form
      className="card rx-form"
      aria-label="Add a doctor"
      onSubmit={(e) => {
        e.preventDefault();
        add.mutate();
      }}
    >
      <h2>Add a doctor</h2>
      <p className="muted small">
        The doctor is listed and bookable once Medicity has checked their registration number.
      </p>
      <label htmlFor="d-name">Full name</label>
      <input id="d-name" required maxLength={120} value={f.fullName} onChange={set("fullName")} />
      <label htmlFor="d-email">Email</label>
      <input id="d-email" type="email" required value={f.email} onChange={set("email")} />
      <label htmlFor="d-phone">Phone (optional)</label>
      <input id="d-phone" inputMode="tel" value={f.phone} onChange={set("phone")} />
      <div className="field-row">
        <div>
          <label htmlFor="d-spec">Specialization</label>
          <input id="d-spec" required maxLength={80} value={f.specialization} onChange={set("specialization")} />
        </div>
        <div>
          <label htmlFor="d-qual">Qualification</label>
          <input id="d-qual" required maxLength={120} value={f.qualification} onChange={set("qualification")} />
        </div>
      </div>
      <div className="field-row">
        <div>
          <label htmlFor="d-council">Medical council</label>
          <input id="d-council" required maxLength={80} value={f.medicalCouncil} onChange={set("medicalCouncil")} />
        </div>
        <div>
          <label htmlFor="d-reg">Registration number</label>
          <input id="d-reg" required maxLength={40} value={f.registrationNumber} onChange={set("registrationNumber")} />
        </div>
      </div>
      <div className="field-row">
        <div>
          <label htmlFor="d-years">Years of experience</label>
          <input id="d-years" type="number" min={0} max={70} value={f.yearsExperience} onChange={set("yearsExperience")} />
        </div>
        <div>
          <label htmlFor="d-fee">Consultation fee (₹)</label>
          <input id="d-fee" type="number" min={0} step="0.01" value={f.consultationFee} onChange={set("consultationFee")} />
        </div>
      </div>
      <FormTail error={add.error} busy={add.isPending} submit="Add doctor" onCancel={onCancel} />
    </form>
  );
}

function FormTail({
  error,
  busy,
  submit,
  onCancel,
}: {
  error: Error | null;
  busy: boolean;
  submit: string;
  onCancel: () => void;
}) {
  return (
    <>
      {error && (
        <p className="error" role="alert">
          {error instanceof ApiError ? error.message : "Could not add. Try again."}
        </p>
      )}
      <div className="rx-form__actions">
        <button type="submit" disabled={busy}>
          {busy ? "Adding…" : submit}
        </button>
        <button type="button" className="link" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </>
  );
}
