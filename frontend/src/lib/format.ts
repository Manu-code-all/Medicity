/**
 * Date formatting for the UI. Everything the API returns is an ISO instant in
 * UTC; these render it in the viewer's own time zone and locale.
 */

export function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, {
    day: "numeric",
    month: "short",
    year: "numeric",
  });
}

export function formatDayLong(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, {
    weekday: "long",
    day: "numeric",
    month: "long",
  });
}

export function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
}

/** Parts for a calendar-style date tile: "12" over "SEP". */
export function dateTile(iso: string): { day: string; month: string } {
  const date = new Date(iso);
  return {
    day: String(date.getDate()),
    month: date.toLocaleDateString(undefined, { month: "short" }).toUpperCase(),
  };
}

/** "in 3 days", "tomorrow", "in 5 hours" — how far away a future instant is. */
export function relativeFromNow(iso: string, now: Date = new Date()): string {
  const diffMs = new Date(iso).getTime() - now.getTime();
  const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });
  const hours = Math.round(diffMs / 3_600_000);
  if (Math.abs(hours) < 24) return rtf.format(hours, "hour");
  return rtf.format(Math.round(diffMs / 86_400_000), "day");
}

/** Age in whole years from a yyyy-mm-dd date of birth. */
export function ageFrom(dateOfBirth: string, now: Date = new Date()): number {
  const [y = 0, m = 1, d = 1] = dateOfBirth.split("-").map(Number);
  let age = now.getFullYear() - y;
  const hadBirthday = now.getMonth() + 1 > m || (now.getMonth() + 1 === m && now.getDate() >= d);
  if (!hadBirthday) age -= 1;
  return age;
}

export function greeting(now: Date = new Date()): string {
  const hour = now.getHours();
  if (hour < 12) return "Good morning";
  if (hour < 17) return "Good afternoon";
  return "Good evening";
}

export function firstName(fullName: string): string {
  return fullName.split(/\s+/)[0] ?? fullName;
}

export function initials(fullName: string): string {
  return fullName
    .replace(/^Dr\.?\s+/i, "")
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? "")
    .join("");
}
