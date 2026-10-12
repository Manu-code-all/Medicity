import type { ReactNode } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { LegalLinks } from "../../components/LegalLinks";
import { GRIEVANCE_OFFICER, LAST_UPDATED, OPERATOR_NAME, POLICY_VERSIONS } from "../../lib/policies";

/**
 * The public legal pages. Every one carries a DRAFT banner: the text was
 * written by the engineers to say what the software really does, and a lawyer
 * must review (and own) it before real patients rely on it. Anything in
 * square brackets is a blank that only the operator can fill.
 */
function LegalPage({ title, version, children }: { title: string; version?: string | undefined; children: ReactNode }) {
  return (
    <article className="legal">
      <p className="legal__draft" role="note">
        <strong>DRAFT, NOT YET REVIEWED BY A LAWYER.</strong> This text describes what Medicity does today. It is not
        legal advice and must be reviewed before it is relied on. Text in [square brackets] is still to be filled in.
      </p>
      <h1>{title}</h1>
      <p className="muted">
        {version && <>Version {version} · </>}Last updated {LAST_UPDATED}
      </p>
      {children}
      <p className="legal__links muted">
        <LegalLinks />
      </p>
    </article>
  );
}

function GrievanceOfficer() {
  return (
    <address className="legal__officer">
      <strong>{GRIEVANCE_OFFICER.name}</strong>
      <br />
      Email: <a href={`mailto:${GRIEVANCE_OFFICER.email}`}>{GRIEVANCE_OFFICER.email}</a>
      <br />
      {GRIEVANCE_OFFICER.address}
    </address>
  );
}

export function PrivacyPage() {
  return (
    <LegalPage title="Privacy notice" version={POLICY_VERSIONS.privacy}>
      <p>
        Medicity helps patients book doctors, keep their prescriptions, and ask chemists nearby whether they have a
        medicine. This notice says what we collect, why, who sees it, how long we keep it, and what you can do. It is
        written to meet India's Digital Personal Data Protection Act, 2023.
      </p>

      <h2>Who is responsible</h2>
      <p>
        {OPERATOR_NAME} decides why and how your data is used (the "data fiduciary"). Contact the Grievance Officer
        below for anything in this notice.
      </p>

      <h2>What we collect</h2>
      <ul>
        <li>
          <strong>Your account:</strong> name, email, mobile number (optional), password (stored only as a one-way
          hash), date of birth and gender.
        </li>
        <li>
          <strong>Your health information:</strong> what you tell us (blood group, height, weight, allergies, long-term
          conditions, current medicines, the reason for a visit, files you attach to a visit) and what your doctors
          record (visit notes, diagnoses, prescriptions).
        </li>
        <li>
          <strong>Your family members:</strong> if you add a parent, child or other relative, the same details for them.
        </li>
        <li>
          <strong>Where you are:</strong> a home location you choose to save, and the place you ask from when you ask
          chemists about a medicine. Chemists see only a distance, never your coordinates.
        </li>
        <li>
          <strong>How you use the service:</strong> a security log of who signed in and who opened which record, with
          the network address of the request.
        </li>
      </ul>

      <h2>Why we use it</h2>
      <ul>
        <li>To run your account, book visits, and let your doctors treat you.</li>
        <li>To send a prescription you choose to the chemists you choose, and to hold a medicine for you.</li>
        <li>To remind you about visits and medicine courses, and to tell you about changes to a booking.</li>
        <li>To keep the service safe: the security log, and limiting repeated sign-in attempts.</li>
        <li>To meet legal duties, including keeping medical records.</li>
      </ul>
      <p>
        We do not sell your data, and we do not use it for advertising. A chemist sees a prescription only after you
        send it to them.
      </p>

      <h2>Your health information and your consent</h2>
      <p>
        We ask for your agreement to use your health information separately from the terms of service, because you
        may agree to one and not the other. Without it we cannot keep your health records, so we cannot offer a patient
        account. You can withdraw it by deleting your account (below). Each agreement is recorded with its version and
        the time, and a new version is asked for again.
      </p>

      <h2>Children and family members</h2>
      <p>
        A person under 18 does not have an account of their own. A parent or guardian adds them as a family member and
        agrees on their behalf; we record that they did so as guardian. An adult relative may also be added by someone
        who looks after them, who confirms they may agree for them.
      </p>

      <h2>Who else handles your data</h2>
      <ul>
        <li>Doctors you book, who see your profile and the history they need to treat you.</li>
        <li>Chemists you send a question to, who see the prescription and your first name and initial.</li>
        <li>
          A clinic that moved its old files into Medicity keeps them as its own records. They are not linked to your
          account, and you do not see them, unless you confirm your email address and say yes.
        </li>
        <li>
          Service providers that run the service for us: hosting and database [provider and region: to be confirmed],
          email delivery (Resend) and text messages (MSG91) for sign-in codes.
        </li>
        <li>
          When a doctor photographs a handwritten prescription and asks the software to read it, the photo is sent to an
          AI service (Anthropic or Google, if configured) to draft the text. [To be confirmed: processor terms.]
        </li>
        <li>Authorities, where the law requires it.</li>
      </ul>
      <p>[To be confirmed by the operator: whether any of this data is stored or processed outside India.]</p>

      <h2>How long we keep it</h2>
      <table className="legal__table">
        <thead>
          <tr><th>Data</th><th>Kept for</th></tr>
        </thead>
        <tbody>
          <tr><td>Where you were when you asked chemists</td><td>Cleared 30 days after the question is closed</td></tr>
          <tr><td>Sign-in sessions, sign-in codes</td><td>Until they expire (days)</td></tr>
          <tr><td>Notifications and your profile details</td><td>Until you delete your account</td></tr>
          <tr>
            <td>Visits, prescriptions, doctors' notes</td>
            <td>
              At least 3 years after treatment, as medical-practice rules require [period to be confirmed by a lawyer]
              , even after your account is deleted, with your name and contact details removed
            </td>
          </tr>
          <tr>
            <td>Records a clinic imported from its old files</td>
            <td>
              Held by the clinic as its own record, for as long as it keeps them [period to be confirmed]. If you accept
              them, you see them and they are included in the copy of your data. Deleting your account ends the link to you; the
              clinic keeps its record. Records you never accepted are never shown to you or linked to your account
            </td>
          </tr>
          <tr><td>Security log</td><td>Kept; it cannot be edited, and names you by an id, not by name</td></tr>
          <tr><td>Record of your agreements</td><td>Kept, as proof of what you agreed to and when</td></tr>
        </tbody>
      </table>

      <h2>Your rights</h2>
      <ul>
        <li>
          <strong>A copy:</strong> in the app, Profile, "Download my data" gives you everything we hold about you and
          the family members you manage, as a file.
        </li>
        <li>
          <strong>Correction:</strong> you can edit your health and contact details in Profile. For other corrections,
          write to the Grievance Officer.
        </li>
        <li>
          <strong>Deletion:</strong> in the app, Profile, "Delete my account". See{" "}
          <Link to="/delete-account">how deletion works</Link>, including what we must keep.
        </li>
        <li>
          <strong>Complaints:</strong> to the Grievance Officer first. If you are not satisfied you may complain to the
          Data Protection Board of India.
        </li>
        <li>
          <strong>Nominate someone</strong> to exercise these rights if you die or cannot: write to the Grievance
          Officer. [Process to be defined.]
        </li>
      </ul>

      <h2>Grievance Officer</h2>
      <GrievanceOfficer />
      <p className="muted">We aim to answer within {GRIEVANCE_OFFICER.replyWithin}.</p>

      <h2>Changes</h2>
      <p>
        When this notice changes materially we change its version and ask you to read and agree again the next time you
        sign in.
      </p>
    </LegalPage>
  );
}

export function TermsPage() {
  return (
    <LegalPage title="Terms of service" version={POLICY_VERSIONS.terms}>
      <h2>The service</h2>
      <p>
        Medicity is a platform where patients find and book doctors, keep prescriptions, and ask nearby chemists what
        they have. Doctors and chemists are independent professionals who sign up themselves. Medicity does not
        provide medical care, and does not employ the doctors or chemists on it.
      </p>

      <h2>Not for emergencies</h2>
      <p>
        If you think someone's life is in danger, call 112 or go to the nearest hospital. Do not use Medicity to wait
        for help. See the <Link to="/disclaimer">medical disclaimer</Link>.
      </p>

      <h2>Your account</h2>
      <ul>
        <li>Give true details and keep your password private. You are responsible for what happens under your account.</li>
        <li>You must be 18 or over to hold an account. Children are added as family members by a parent or guardian.</li>
        <li>Tell us if you think someone else has used your account.</li>
      </ul>

      <h2>Patients</h2>
      <ul>
        <li>A booking holds a slot for you with one doctor. Cancel it if you cannot come, so someone else can have it.</li>
        <li>
          A chemist's answer ("yes, partly, no", with a price) is what that chemist said at that moment. Stock and
          prices can change before you arrive.
        </li>
        <li>Consultation fees are paid to the doctor under their own arrangements. [Payment terms: to be confirmed.]</li>
      </ul>

      <h2>Doctors and chemists</h2>
      <ul>
        <li>You confirm that your registration number or drug licence is genuine and in force. We may check it, and may remove a listing we cannot verify.</li>
        <li>You are responsible for your own clinical and dispensing decisions and for keeping the records the law requires of you.</li>
        <li>You use patients' information only to treat or serve them.</li>
      </ul>

      <h2>Using the service properly</h2>
      <p>
        Do not misuse it: no false bookings, no scraping, no attempts to reach other people's records, no uploading of
        anything unlawful or harmful.
      </p>

      <h2>Our responsibility</h2>
      <p>
        We work to keep the service available and accurate, but it is provided as it is. [Limitation of liability,
        governing law and courts: to be drafted by a lawyer.]
      </p>

      <h2>Ending the relationship</h2>
      <p>
        You can delete your account at any time (<Link to="/delete-account">how</Link>). We may suspend an account that
        breaks these terms.
      </p>

      <h2>Contact</h2>
      <GrievanceOfficer />
    </LegalPage>
  );
}

export function DisclaimerPage() {
  return (
    <LegalPage title="Medical disclaimer" version={POLICY_VERSIONS.terms}>
      <p>
        <strong>Medicity is not a doctor and does not give medical advice.</strong> It helps you reach doctors and
        chemists. Read this before relying on anything it shows.
      </p>
      <h2>Emergencies</h2>
      <p>
        Chest pain, trouble breathing, heavy bleeding, sudden weakness or confusion, a seizure, a serious injury or
        thoughts of harming yourself are emergencies. Call 112 (or your local emergency number) or go to a hospital now.
      </p>
      <h2>The body guide and symptom search</h2>
      <p>
        The "where does it hurt" guide and the symptom box suggest which kind of doctor to see. They are not a
        diagnosis. They read fixed words and phrases in English, can miss or misread what you describe, and were
        written without a clinician's review. [A doctor's review is still to be done.] When in doubt, see a doctor.
      </p>
      <h2>Prescriptions and medicine instructions</h2>
      <p>
        Your doctor's prescription is what to follow. The explanations of how to take a medicine, including those in
        Indian languages, are a convenience that has not been checked by a pharmacist or a native speaker. If they differ
        from what your doctor or chemist told you, ask them.
      </p>
      <h2>Chemists and stock</h2>
      <p>
        A chemist's answer is what they said when they gave it. Stock can run out. A brand a chemist offers instead is
        used only if your doctor allowed a substitute, and you should check it with the chemist.
      </p>
      <h2>Doctors</h2>
      <p>
        Doctors on Medicity are independent. Registration numbers are checked by a person against the medical council
        register, by hand; this is not a guarantee of any doctor's skill or conduct. Reviews are patients' opinions.
      </p>
      <h2>Video visits</h2>
      <p>
        A video visit has limits. A doctor may ask you to be examined in person. Calls are not recorded, and may fail if
        your connection is poor.
      </p>
    </LegalPage>
  );
}

export function DeleteAccountPage() {
  const [params] = useSearchParams();
  const done = params.get("done") === "1";
  return (
    <LegalPage title="Delete your Medicity account">
      {done && (
        <p className="legal__done" role="status">
          Your account has been deleted. You have been signed out everywhere.
        </p>
      )}
      <p>
        This page explains how to delete your Medicity account and the data in it. It applies to the Medicity web app
        and the Medicity app, both run by {OPERATOR_NAME}.
      </p>

      <h2>Patients: delete it yourself in the app</h2>
      <ol>
        <li>Sign in to Medicity.</li>
        <li>Open <strong>My portal</strong>, then <strong>Profile</strong>.</li>
        <li>Under <strong>Your data</strong>, choose <strong>Delete my account</strong>.</li>
        <li>Read the warning, type your password, and confirm.</li>
      </ol>
      <p>
        You can first choose <strong>Download my data</strong> on the same page to keep a copy. Deleting cannot be
        undone.
      </p>

      <h2>Cannot sign in, or you are a doctor or chemist?</h2>
      <p>
        Email the Grievance Officer from the email address on the account, with the subject "Delete my Medicity
        account". We will check it is you, then delete the account, and reply within {GRIEVANCE_OFFICER.replyWithin}.
        Doctor and chemist accounts are removed this way, by a person, because other people rely on their listing.
      </p>
      <GrievanceOfficer />

      <h2>What is deleted straight away</h2>
      <ul>
        <li>Your name, email, mobile number and password. The account can no longer be signed in to.</li>
        <li>Your address, emergency contact, height, weight, allergies, conditions, medicines and saved home location.</li>
        <li>The same details for family members you manage, and their names.</li>
        <li>Where you were when you asked chemists about a medicine.</li>
        <li>Notifications, sign-in codes, waiting-list places and walk-in places, and files you attached to visits.</li>
        <li>Every sign-in session, on every device.</li>
        <li>Your upcoming visits are cancelled, and any medicine held for you is released.</li>
        <li>The written comments on your reviews.</li>
      </ul>

      <h2>What we keep, and why</h2>
      <ul>
        <li>
          <strong>Medical records:</strong> your past visits, the prescriptions your doctors wrote, and what the
          pharmacy dispensed. Medical-practice rules in India require doctors to keep these for years [exact period to
          be confirmed by a lawyer]. They are kept without your name or contact details; your date of birth is reduced
          to its year.
        </li>
        <li>
          <strong>The security log:</strong> a record of who signed in and who opened which record. It cannot be edited
          and names the account by an id only.
        </li>
        <li>
          <strong>The record of what you agreed to</strong> and when, as proof.
        </li>
        <li>The star ratings you gave, which are part of a doctor's average and carry no name.</li>
      </ul>
      <p>
        Backups are replaced on the provider's normal cycle, so a deleted account can remain in a backup for a short
        time [period: to be confirmed]. It is not restored into the live service.
      </p>

      <h2>Questions</h2>
      <p>
        Read the <Link to="/privacy">privacy notice</Link>, or write to the Grievance Officer above.
      </p>
    </LegalPage>
  );
}
