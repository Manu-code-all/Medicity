/** A calendar date as yyyy-mm-dd in the browser's own time zone, which is what date inputs and the API's date parameters use. */
export function isoDay(d: Date): string {
  const month = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${d.getFullYear()}-${month}-${day}`;
}

/** The day `days` away from `from` (negative for earlier). */
export function addDays(from: Date, days: number): Date {
  const next = new Date(from);
  next.setDate(next.getDate() + days);
  return next;
}

/** "Mon 12 Oct" for a yyyy-mm-dd date, without time-zone drift. */
export function shortDay(iso: string): string {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y!, m! - 1, d).toLocaleDateString("en-IN", { weekday: "short", day: "numeric", month: "short" });
}
