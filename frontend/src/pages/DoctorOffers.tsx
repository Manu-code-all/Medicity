import type { Doctor } from "../api/types";

const rupees = (n: number) => `₹${n.toLocaleString("en-IN")}`;

/** Accepted insurance as short tags: the first three, then how many more. */
export function InsurerTags({ insurers }: { insurers: string[] }) {
  if (insurers.length === 0) return null;
  const shown = insurers.slice(0, 3);
  return (
    <p className="insurers" aria-label={`Accepts ${insurers.join(", ")}`}>
      <span className="muted small">Accepts</span>
      {shown.map((i) => (
        <span key={i} className="tag">
          {i}
        </span>
      ))}
      {insurers.length > shown.length && <span className="muted small">+{insurers.length - shown.length} more</span>}
    </p>
  );
}

/**
 * What a visit costs before anyone arrives: the consultation fee plus any
 * charge added to every visit, then the other procedures the clinic prices.
 */
export function PriceList({ doctor }: { doctor: Doctor }) {
  const prices = doctor.prices ?? [];
  const everyVisit = prices.filter((p) => p.everyVisit);
  const others = prices.filter((p) => !p.everyVisit);
  const perVisit = doctor.consultationFee + everyVisit.reduce((sum, p) => sum + p.priceInr, 0);
  if (prices.length === 0) return null;

  return (
    <details className="price-list">
      <summary>Price list</summary>
      <table>
        <caption className="sr-only">Prices at {doctor.fullName}'s clinic</caption>
        <tbody>
          <tr>
            <th scope="row">Consultation</th>
            <td>{rupees(doctor.consultationFee)}</td>
          </tr>
          {everyVisit.map((p) => (
            <tr key={p.procedure}>
              <th scope="row">
                {p.procedure} <span className="muted small">(every visit)</span>
              </th>
              <td>{rupees(p.priceInr)}</td>
            </tr>
          ))}
          {everyVisit.length > 0 && (
            <tr className="price-list__total">
              <th scope="row">Each visit</th>
              <td>{rupees(perVisit)}</td>
            </tr>
          )}
          {others.map((p) => (
            <tr key={p.procedure}>
              <th scope="row">{p.procedure}</th>
              <td>{p.priceInr === 0 ? "No charge" : rupees(p.priceInr)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="muted small">Set by the clinic. Covered amounts depend on your policy.</p>
    </details>
  );
}
