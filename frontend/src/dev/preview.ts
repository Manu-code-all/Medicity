/**
 * UI preview without a backend or a sign-in: `npm run preview:ui`, then open
 * /portal?as=patient, /doctor?as=doctor or /store/requests?as=chemist.
 *
 * Development only. It replaces `fetch` with recorded answers from the public
 * demo (fixtures/*.json hold no tokens or passwords) and sets the display
 * session. Writes succeed and change nothing. It is never part of a
 * production build: main.tsx loads it only when import.meta.env.DEV is true.
 */
import { SESSION_KEY } from "../auth/context";

type Role = "patient" | "doctor" | "chemist";

interface Fixture {
  session: { userId: string; fullName: string; role: string };
  responses: Record<string, unknown>;
}

const ROLE_KEY = "medicity.preview.role";

function json(status: number, body: unknown) {
  return new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

export async function install() {
  const asked = new URLSearchParams(location.search).get("as") as Role | null;
  const role = asked ?? (sessionStorage.getItem(ROLE_KEY) as Role | null) ?? "patient";
  sessionStorage.setItem(ROLE_KEY, role);

  const fixture = ((await import(`./fixtures/${role}.json`)) as { default: Fixture }).default;
  localStorage.setItem(SESSION_KEY, JSON.stringify(fixture.session));
  localStorage.setItem("medicity.access", "preview");
  localStorage.setItem("medicity.refresh", "preview");

  const byPath = new Map<string, unknown>();
  for (const [key, body] of Object.entries(fixture.responses)) {
    byPath.set(key, body);
    // Dates in queries differ from day to day; the path alone is the fallback.
    const bare = key.split("?")[0] ?? key;
    if (!byPath.has(bare)) byPath.set(bare, body);
  }

  window.fetch = async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = new URL(String(input instanceof Request ? input.url : input), location.origin);
    if (!url.pathname.startsWith("/api/")) return new Response(null, { status: 404 });
    const method = (init.method ?? "GET").toUpperCase();
    if (method !== "GET") return json(200, {});
    const hit = byPath.get(url.pathname + url.search) ?? byPath.get(url.pathname);
    if (hit !== undefined) return json(200, hit);
    console.warn("[preview] no fixture for", url.pathname + url.search);
    // Lists the recorder did not visit are empty rather than errors.
    return json(200, []);
  };
}
