/* Medicity service worker: an offline app shell and nothing else.
 *
 * What it caches: the HTML shell, the favicon/icons, and the content-hashed
 * build files under /assets/ (JS, CSS). All of those are identical for every
 * visitor and contain no personal data.
 *
 * What it never touches: /api/*, WebSockets, any non-GET request, any other
 * origin, or any response that is not a plain 200. Patient data therefore never
 * lands in Cache Storage; offline, API calls simply fail and the app shows its
 * normal error states.
 *
 * Bump VERSION to drop every old cache on the next visit.
 */
const VERSION = "v1";
const SHELL_CACHE = `medicity-shell-${VERSION}`;
const ASSET_CACHE = `medicity-assets-${VERSION}`;
const SHELL_URL = "/index.html";
const PRECACHE = [SHELL_URL, "/favicon.svg", "/icons/icon-192.png", "/icons/icon-512.png"];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(SHELL_CACHE).then((cache) => cache.addAll(PRECACHE)));
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(
          keys
            .filter((k) => k.startsWith("medicity-") && k !== SHELL_CACHE && k !== ASSET_CACHE)
            .map((k) => caches.delete(k)),
        ),
      )
      .then(() => self.clients.claim()),
  );
});

function cacheable(response) {
  return response && response.status === 200 && response.type === "basic";
}

self.addEventListener("fetch", (event) => {
  const { request } = event;
  if (request.method !== "GET") return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname.startsWith("/api/") || url.pathname.startsWith("/ws/")) return;

  // Page loads: network first so a deploy is picked up at once; the one shared
  // shell (the same index.html for every route) is the offline fallback.
  if (request.mode === "navigate") {
    event.respondWith(
      fetch(request)
        .then((response) => {
          const type = response.headers.get("content-type") || "";
          if (cacheable(response) && type.includes("text/html")) {
            const copy = response.clone();
            event.waitUntil(caches.open(SHELL_CACHE).then((c) => c.put(SHELL_URL, copy)));
          }
          return response;
        })
        .catch(() => caches.match(SHELL_URL, { cacheName: SHELL_CACHE }).then((hit) => hit || Response.error())),
    );
    return;
  }

  // Hashed build output never changes under the same name: cache first.
  if (url.pathname.startsWith("/assets/")) {
    event.respondWith(
      caches.open(ASSET_CACHE).then((cache) =>
        cache.match(request).then(
          (hit) =>
            hit ||
            fetch(request).then((response) => {
              if (cacheable(response)) cache.put(request, response.clone());
              return response;
            }),
        ),
      ),
    );
    return;
  }

  // Icons and favicon: serve the precached copy, fall back to the network.
  if (url.pathname.startsWith("/icons/") || url.pathname === "/favicon.svg") {
    event.respondWith(caches.match(request, { cacheName: SHELL_CACHE }).then((hit) => hit || fetch(request)));
  }
});
