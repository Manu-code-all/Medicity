import { beforeEach, describe, expect, it, vi } from "vitest";
import { json, mockFetch } from "../test/fetchMock";
import { ApiError, request, revokeSession, tokenStore } from "./client";
import type { TokenPair } from "./types";

const pair = (n: number): TokenPair => ({
  accessToken: `access-${n}`,
  refreshToken: `refresh-${n}`,
  userId: "u1",
  role: "PATIENT",
  fullName: "Meera",
});

const isRefresh = (url: string) => url.endsWith("/api/v1/auth/refresh");

beforeEach(() => {
  tokenStore.save(pair(1));
});

describe("request", () => {
  it("sends the bearer token and any extra headers", async () => {
    const calls = mockFetch(() => json(200, { ok: true }));

    await request("/api/v1/appointments", {
      method: "POST",
      body: { slotId: "s1" },
      headers: { "Idempotency-Key": "k1" },
    });

    expect(calls[0]?.headers).toMatchObject({
      Authorization: "Bearer access-1",
      "Content-Type": "application/json",
      "Idempotency-Key": "k1",
    });
  });

  it("turns an RFC 9457 body into an ApiError carrying the stable code", async () => {
    mockFetch(() => json(409, { detail: "Taken", code: "SLOT_ALREADY_BOOKED" }));

    const error = await request("/x").catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 409, code: "SLOT_ALREADY_BOOKED", message: "Taken" });
  });

  it("survives a proxy error page that is not JSON", async () => {
    mockFetch(() => new Response("<html>Bad gateway</html>", { status: 502 }));

    const error = await request("/x").catch((e: unknown) => e);

    expect(error).toMatchObject({ status: 502, code: "UNKNOWN" });
  });
});

describe("refreshing an expired access token", () => {
  it("refreshes once for many simultaneous 401s, then replays every request", async () => {
    const calls = mockFetch(({ url, headers }) => {
      if (isRefresh(url)) return json(200, pair(2));
      return headers.Authorization === "Bearer access-2" ? json(200, { url }) : json(401, {});
    });

    const results = await Promise.all([request("/a"), request("/b"), request("/c")]);

    // A second refresh would spend refresh-1 again, which the server treats as
    // theft and answers by ending the session.
    expect(calls.filter((c) => isRefresh(c.url))).toHaveLength(1);
    expect(results).toHaveLength(3);
    expect(tokenStore.refresh()).toBe("refresh-2");
  });

  it("uses the token another tab already obtained instead of spending the old one", async () => {
    // Another tab holds the lock, refreshes, and stores the new pair; this tab
    // gets the lock afterwards and must not send refresh-1.
    vi.stubGlobal("navigator", {
      locks: {
        request: async (_name: string, task: () => Promise<boolean>) => {
          tokenStore.save(pair(2));
          return task();
        },
      },
    });
    const calls = mockFetch(({ url, headers }) => {
      if (isRefresh(url)) return json(200, pair(99));
      return headers.Authorization === "Bearer access-2" ? json(200, {}) : json(401, {});
    });

    await request("/a");

    expect(calls.some((c) => isRefresh(c.url))).toBe(false);
    expect(calls.at(-1)?.headers.Authorization).toBe("Bearer access-2");
  });

  it("signs out locally when the refresh is refused", async () => {
    mockFetch(({ url }) => (isRefresh(url) ? json(401, {}) : json(401, { code: "UNAUTHENTICATED" })));

    const error = await request("/a").catch((e: unknown) => e);

    expect(error).toMatchObject({ status: 401 });
    expect(tokenStore.access()).toBeNull();
    expect(tokenStore.refresh()).toBeNull();
  });

  it("replays the request only once, so a server that always says 401 cannot loop", async () => {
    const calls = mockFetch(({ url }) => (isRefresh(url) ? json(200, pair(2)) : json(401, {})));

    await request("/a").catch(() => undefined);

    expect(calls.filter((c) => c.url === "/a")).toHaveLength(2);
  });
});

describe("revokeSession", () => {
  it("sends the refresh token to the logout endpoint", () => {
    const calls = mockFetch(() => json(204, null));

    revokeSession();

    expect(calls[0]).toMatchObject({ url: "/api/v1/auth/logout", method: "POST", body: { refreshToken: "refresh-1" } });
  });

  it("does nothing when there is no session", () => {
    tokenStore.clear();
    const calls = mockFetch(() => json(204, null));

    revokeSession();

    expect(calls).toHaveLength(0);
  });
});
