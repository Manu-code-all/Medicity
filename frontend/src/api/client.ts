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
 * In-flight refresh, shared across callers.
 *
 * When an access token expires, every query on screen fails with 401 at roughly
 * the same moment. Without this, each one would fire its own refresh request —
 * a thundering herd where all but one response is discarded, and where a
 * rotating-refresh-token scheme would invalidate the tokens of its own siblings.
 * Holding a single promise means the first 401 refreshes and the rest await it.
 */
let refreshInFlight: Promise<boolean> | null = null;

async function refreshTokens(): Promise<boolean> {
  const refreshToken = tokenStore.refresh();
  if (!refreshToken) return false;

  refreshInFlight ??= (async () => {
    try {
      const response = await fetch(`${BASE_URL}/api/v1/auth/refresh`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refreshToken }),
      });
      if (!response.ok) {
        tokenStore.clear();
        return false;
      }
      tokenStore.save((await response.json()) as TokenPair);
      return true;
    } catch {
      return false;
    } finally {
      // Cleared in `finally` so a failed refresh does not poison every
      // subsequent attempt with a permanently rejected promise.
      refreshInFlight = null;
    }
  })();

  return refreshInFlight;
}

interface RequestOptions {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  /** Internal: prevents an infinite refresh loop on a retried request. */
  retrying?: boolean;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = "GET", body, retrying = false } = options;

  const headers: Record<string, string> = {};
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
