import type { CapacitorConfig } from "@capacitor/cli";

/**
 * Capacitor wraps the production build (dist/) in a native Android shell.
 *
 * The web app reads its API address from VITE_API_BASE_URL at BUILD time, so set
 * it when building for Android (see docs/ANDROID.md); inside the shell the page
 * is served from https://localhost and a relative "/api" would go nowhere:
 *
 *   VITE_API_BASE_URL=https://api.example.com npm run android:sync
 *
 * The API must list the shell's origin (https://localhost) in MEDICITY_CORS_ORIGINS.
 */
const config: CapacitorConfig = {
  // TODO(owner): replace with your permanent Play Store application id. It can
  // never be changed after the first upload, so decide before publishing.
  appId: process.env.CAP_APP_ID ?? "com.medicity.app",
  appName: "Medicity",
  webDir: "dist",
  // Kept outside frontend/ so the native project is its own top-level folder.
  android: {
    path: "../android",
  },
  server: {
    // https://localhost: secure context (geolocation, camera) and no cleartext.
    androidScheme: "https",
    // Live-reload during development only, e.g. CAP_SERVER_URL=http://10.0.2.2:5173
    // (10.0.2.2 is the emulator's alias for the host machine). Never set for release.
    ...(process.env.CAP_SERVER_URL
      ? { url: process.env.CAP_SERVER_URL, cleartext: process.env.CAP_SERVER_URL.startsWith("http://") }
      : {}),
  },
  // Release builds must not be debuggable over chrome://inspect.
  loggingBehavior: "none",
  // TODO(owner): push notifications need @capacitor/push-notifications plus a
  // Firebase project and google-services.json. Deliberately not added here.
};

export default config;
