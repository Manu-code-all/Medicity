import type { ProblemDetail, TokenPair } from "./types";

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "";

const ACCESS_KEY = "medicity.access";
const REFRESH_KEY = "medicity.refresh";

/**
 * Error thrown for any non-2xx response, carrying the parsed RFC 9457 body.
 *
 * The `code` is what callers branch on — `SLOT_ALREADY_BOOKED` versus
 * `SLOT_TOO_SOON` need different messages in the UI, and matching on the prose
 * would break the moment someone rewords it.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fieldErrors: Record<string, string>;

  constructor(status: number, problem: Partial<ProblemDetail>) {
    super(problem.detail ?? "Request failed");
    this.name = "ApiError";
    this.status = status;
    this.code = problem.code ?? "UNKNOWN";
    this.fieldErrors = problem.fieldErrors ?? {};
  }
}

export const tokenStore = {
  access: () => localStorage.getItem(ACCESS_KEY),
  refresh: () => localStorage.getItem(REFRESH_KEY),
  save(pair: TokenPair) {
    localStorage.setItem(ACCESS_KEY, pair.accessToken);
    localStorage.setItem(REFRESH_KEY, pair.refreshToken);
  },
  clear() {
    localStorage.removeItem(ACCESS_KEY);
    localStorage.removeItem(REFRESH_KEY);
  },
};

/**
 * In-flight refresh, shared across callers in this tab.
 *
 * When an access token expires, every query on screen fails with 401 at roughly
 * the same moment. Without this, each one would fire its own refresh request.
 * Refresh tokens are single-use and the server treats a second use as theft,
 * ending the session, so the second request would sign the user out.
 * Holding a single promise means the first 401 refreshes and the rest await it.
 */
let refreshInFlight: Promise<boolean> | null = null;

async function refreshTokens(): Promise<boolean> {
  const seen = tokenStore.refresh();
  if (!seen) return false;

  refreshInFlight ??= withRefreshLock(async () => {
    try {
      // Other tabs share these tokens through localStorage. If one refreshed
      // while this tab waited for the lock, the token this tab saw is spent;
      // sending it would look like theft. Use the new one instead.
      const current = tokenStore.refresh();
      if (current !== seen) return current !== null;

      const response = await fetch(`${BASE_URL}/api/v1/auth/refresh`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refreshToken: current }),
      });
      if (!response.ok) {
        tokenStore.clear();
        return false;
      }
      tokenStore.save((await response.json()) as TokenPair);
      return true;
    } catch {
      return false;
    }
  }).finally(() => {
    // Cleared in `finally` so a failed refresh does not poison every
    // subsequent attempt with a permanently rejected promise.
    refreshInFlight = null;
  });

  return refreshInFlight;
}

/**
 * Runs `task` while holding a lock shared by every tab of this origin, so two
 * tabs never refresh at once. Browsers without the Web Locks API run it
 * directly: at worst a simultaneous refresh in two tabs signs the user out.
 */
async function withRefreshLock(task: () => Promise<boolean>): Promise<boolean> {
  if (typeof navigator !== "undefined" && "locks" in navigator) {
    return await navigator.locks.request("medicity.refresh", task);
  }
  return task();
}

/** Ends the session on the server. Best effort: signing out locally must never wait on or fail with it. */
export function revokeSession(): void {
  const refreshToken = tokenStore.refresh();
  if (!refreshToken) return;
  void fetch(`${BASE_URL}/api/v1/auth/logout`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken }),
    keepalive: true,
  }).catch(() => undefined);
}

interface RequestOptions {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  headers?: Record<string, string>;
  /** Internal: prevents an infinite refresh loop on a retried request. */
  retrying?: boolean;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = "GET", body, retrying = false } = options;

  const headers: Record<string, string> = { ...options.headers };
  if (body !== undefined) headers["Content-Type"] = "application/json";

  const accessToken = tokenStore.access();
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;

  const response = await fetch(`${BASE_URL}${path}`, {
    method,
    headers,
    body: body === undefined ? null : JSON.stringify(body),
  });

  // Refresh once, then replay the original request. `retrying` stops a server
  // that returns 401 even for valid tokens from causing an endless loop.
  if (response.status === 401 && !retrying && tokenStore.refresh()) {
    if (await refreshTokens()) {
      return request<T>(path, { ...options, retrying: true });
    }
    tokenStore.clear();
  }

  if (response.status === 204) {
    return undefined as T;
  }

  if (!response.ok) {
    // A 502 from a proxy is HTML, not problem+json. Parsing defensively keeps
    // an infrastructure hiccup from surfacing as an unhandled SyntaxError.
    let problem: Partial<ProblemDetail> = {};
    try {
      problem = (await response.json()) as Partial<ProblemDetail>;
    } catch {
      problem = { detail: `Request failed with status ${response.status}` };
    }
    throw new ApiError(response.status, problem);
  }

  return (await response.json()) as T;
}
