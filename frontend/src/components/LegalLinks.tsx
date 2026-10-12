import { Link } from "react-router-dom";

/** The public legal pages, as a row of links: footers and sign-up forms. */
export function LegalLinks({ className }: { className?: string }) {
  return (
    <span className={className}>
      <Link to="/privacy">Privacy notice</Link>
      {" · "}
      <Link to="/terms">Terms of service</Link>
      {" · "}
      <Link to="/disclaimer">Medical disclaimer</Link>
      {" · "}
      <Link to="/delete-account">Delete your account</Link>
    </span>
  );
}
