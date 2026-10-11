/**
 * Registers the offline-shell service worker (public/sw.js).
 *
 * Production web only: skipped in dev (it would cache stale modules), inside the
 * Capacitor Android shell (assets are already bundled in the app), and where the
 * browser has no support. Registration waits for the page load so it never
 * competes with the first paint, and any failure is swallowed: the app works
 * the same without it.
 */
export function registerServiceWorker(): void {
  if (!import.meta.env.PROD) return;
  if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) return;
  if ((window as { Capacitor?: { isNativePlatform?: () => boolean } }).Capacitor?.isNativePlatform?.()) return;

  const register = () => {
    navigator.serviceWorker.register("/sw.js").catch(() => {});
  };
  if (document.readyState === "complete") register();
  else window.addEventListener("load", register, { once: true });
}
