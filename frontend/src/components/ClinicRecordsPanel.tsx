import { Fragment, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../api/client";
import { clinicRecords } from "../api/endpoints";
import type { LinkedClinicRecord } from "../api/types";

const KEY = ["clinic-records"];

/**
 * Records a clinic brought in from its old files. They are the clinic's, and
 * the patient sees none of it until the email address is proved with a code
 * and they say yes to each clinic. Saying no keeps it hidden for good.
 */
export function ClinicRecordsPanel() {
  const queryClient = useQueryClient();
  const view = useQuery({ queryKey: KEY, queryFn: clinicRecords.view, retry: false });
  const refresh = () => void queryClient.invalidateQueries({ queryKey: KEY });

  const accept = useMutation({ mutationFn: clinicRecords.accept, onSuccess: refresh });
  const decline = useMutation({ mutationFn: clinicRecords.decline, onSuccess: refresh });

  // Nothing to say while it loads or if it cannot be read: the profile page is not about this.
  if (view.isPending || view.isError || !Array.isArray(view.data.offers) || !Array.isArray(view.data.linked)) {
    return null;
  }
  const { emailVerified, offers, linked } = view.data;

  return (
    <section className="card stack" aria-labelledby="clinic-records">
      <div>
        <h2 id="clinic-records" className="portal__subtitle">
          Records from your clinic
        </h2>
        <p className="muted small">
          A clinic you have visited may have moved its old files into Medicity. They stay hidden from you, and are not
          linked to your account, unless you say yes.
        </p>
      </div>

      {!emailVerified && <VerifyEmail onVerified={refresh} />}

      {emailVerified && offers.length === 0 && linked.length === 0 && (
        <p className="muted">Nothing is waiting for you.</p>
      )}

      {offers.map((o) => (
        <div key={o.id} className="clinic-offer">
          <p>
            <strong>{o.clinicName}</strong> already has records for you. Link them to your account?
            <span className="muted small"> You will see what they hold. If you say no, they stay with the clinic and are not shown to you.</span>
          </p>
          <div className="clinic-offer__actions">
            <button type="button" disabled={accept.isPending || decline.isPending} onClick={() => accept.mutate(o.id)}>
              Yes, link them
            </button>
            <button
              type="button"
              className="button--ghost"
              disabled={accept.isPending || decline.isPending}
              onClick={() => decline.mutate(o.id)}
            >
              No thanks
            </button>
          </div>
        </div>
      ))}
      {(accept.isError || decline.isError) && (
        <p className="error" role="alert">
          That did not go through. Refresh the page and try again.
        </p>
      )}

      {linked.map((r) => (
        <Linked key={r.id} record={r} />
      ))}
    </section>
  );
}

function VerifyEmail({ onVerified }: { onVerified: () => void }) {
  const [code, setCode] = useState("");
  const send = useMutation({ mutationFn: clinicRecords.sendCode });
  const verify = useMutation({ mutationFn: () => clinicRecords.verifyCode(code.trim()), onSuccess: onVerified });
  const sent = send.data && send.data.delivery !== "UNAVAILABLE";

  return (
    <div className="stack">
      <p>
        To see whether a clinic holds records for you, first confirm that this email address is yours. We email you a
        code.
      </p>
      {!sent && (
        <div className="import-actions">
          <button type="button" className="button--ghost" disabled={send.isPending} onClick={() => send.mutate()}>
            {send.isPending ? "Sending…" : "Email me a code"}
          </button>
        </div>
      )}
      {send.data?.delivery === "UNAVAILABLE" && (
        <p className="notice" role="status">
          Emailing codes is not available right now. Try again later.
        </p>
      )}
      {send.isError && (
        <p className="error" role="alert">
          {send.error instanceof ApiError ? send.error.message : "Could not send the code."}
        </p>
      )}
      {sent && (
        <form
          className="days-off__form"
          onSubmit={(e) => {
            e.preventDefault();
            if (code.trim()) verify.mutate();
          }}
        >
          <label>
            Code{send.data?.sentTo ? ` sent to ${send.data.sentTo}` : ""}
            <input
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={6}
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
            />
          </label>
          <button type="submit" disabled={verify.isPending || code.trim().length !== 6}>
            {verify.isPending ? "Checking…" : "Confirm"}
          </button>
        </form>
      )}
      {send.data?.demoCode && <p className="muted small">Demo account: your code is {send.data.demoCode}.</p>}
      {verify.isError && (
        <p className="error" role="alert">
          {verify.error instanceof ApiError ? verify.error.message : "That code did not work."}
        </p>
      )}
    </div>
  );
}

function Linked({ record: r }: { record: LinkedClinicRecord }) {
  const rows: [string, string | null][] = [
    ["Name", r.fullName],
    ["Phone", r.phone],
    ["Email", r.email],
    ["Date of birth", r.dateOfBirth],
    ["Gender", r.gender ? r.gender.charAt(0) + r.gender.slice(1).toLowerCase() : null],
    ["Blood group", r.bloodGroup],
    ["Allergies", r.allergies],
    ["Notes from the clinic", r.notes],
  ];
  return (
    <div>
      <h3 className="portal__subtitle">{r.clinicName}</h3>
      <dl className="details">
        {rows
          .filter(([, v]) => v)
          .map(([label, value]) => (
            <Fragment key={label}>
              <dt>{label}</dt>
              <dd style={{ whiteSpace: "pre-wrap" }}>{value}</dd>
            </Fragment>
          ))}
      </dl>
    </div>
  );
}
