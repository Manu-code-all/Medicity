import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

type Listener = (event: unknown) => void;

/** Loads public/sw.js against a fake worker scope and returns its fetch handler. */
function loadWorker() {
  const source = readFileSync(resolve(__dirname, "../../public/sw.js"), "utf8");
  const listeners: Record<string, Listener> = {};
  const self = {
    location: { origin: "https://medicity.test" },
    addEventListener: (type: string, fn: Listener) => (listeners[type] = fn),
    skipWaiting: () => undefined,
    clients: { claim: () => Promise.resolve() },
  };
  const cache = { match: () => Promise.resolve(undefined), put: () => Promise.resolve() };
  const caches = { open: () => Promise.resolve(cache), match: () => Promise.resolve(undefined) };
  const pending = () => new Promise(() => undefined);
  new Function("self", "caches", "fetch", "Response", source)(self, caches, pending, class {});
  return listeners;
}

function fetchEvent(url: string, init: { method?: string; mode?: string } = {}) {
  let responded = false;
  return {
    event: {
      request: { url, method: init.method ?? "GET", mode: init.mode ?? "cors" },
      respondWith: () => (responded = true),
      waitUntil: () => undefined,
    },
    handled: () => responded,
  };
}

describe("service worker", () => {
  const { fetch: onFetch } = loadWorker();

  it("never intercepts API, socket, cross-origin or non-GET requests", () => {
    for (const [url, init] of [
      ["https://medicity.test/api/v1/patients/me", {}],
      ["https://medicity.test/api/v1/auth/login", { method: "POST" }],
      ["https://medicity.test/ws/video?ticket=x", {}],
      ["https://api.other.test/api/v1/doctors", {}],
      ["https://medicity.test/assets/app.js", { method: "POST" }],
    ] as const) {
      const probe = fetchEvent(url, init);
      onFetch?.(probe.event);
      expect(probe.handled(), url).toBe(false);
    }
  });

  it("handles page loads and hashed assets", () => {
    for (const [url, init] of [
      ["https://medicity.test/appointments", { mode: "navigate" }],
      ["https://medicity.test/assets/index-abc.js", {}],
    ] as const) {
      const probe = fetchEvent(url, init);
      onFetch?.(probe.event);
      expect(probe.handled(), url).toBe(true);
    }
  });
});
