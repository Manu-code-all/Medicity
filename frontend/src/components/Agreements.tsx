import { Link } from "react-router-dom";

interface AgreementProps {
  checked: boolean;
  onChange: (checked: boolean) => void;
  error?: string | undefined;
}

/**
 * The tick boxes on sign-up forms. Neither is ticked for the person, and
 * they are two boxes, not one: agreeing to the terms and agreeing to Medicity
 * keeping your health records are separate decisions (DPDP Act: consent
 * specific to its purpose). The legal pages open in a new tab, so the form is
 * not lost by reading them.
 */
export function TermsAgreement({ checked, onChange, error }: AgreementProps) {
  return (
    <div className="agreement">
      <label className="check">
        <input type="checkbox" required checked={checked} onChange={(e) => onChange(e.target.checked)} />
        <span>
          I agree to the{" "}
          <Link to="/terms" target="_blank" rel="noreferrer">Terms of service</Link> and have read the{" "}
          <Link to="/privacy" target="_blank" rel="noreferrer">Privacy notice</Link>.
        </span>
      </label>
      {error && <small className="error">{error}</small>}
    </div>
  );
}

export function HealthDataAgreement({ checked, onChange, error }: AgreementProps) {
  return (
    <div className="agreement">
      <label className="check">
        <input type="checkbox" required checked={checked} onChange={(e) => onChange(e.target.checked)} />
        <span>
          I agree to Medicity keeping and using my health information (my profile, visits and prescriptions) to run my
          account, as the <Link to="/privacy" target="_blank" rel="noreferrer">Privacy notice</Link> describes. I can
          withdraw this by deleting my account.
        </span>
      </label>
      {error && <small className="error">{error}</small>}
    </div>
  );
}
