import { defineConfig } from "@playwright/test";

/**
 * End-to-end tests drive the built frontend in a real browser against a
 * running API. Nothing is started from here: CI (and a developer) start
 * Postgres, the API in the demo profile and `vite preview`, so the same specs
 * run against whatever stack E2E_BASE_URL points at.
 *
 *   E2E_BASE_URL     where the built frontend is served (default http://localhost:4173)
 *   PW_CHROMIUM_PATH use an already-installed Chromium instead of Playwright's own
 *
 * The specs share one seeded database and rely on the demo seed's data, so they
 * run one at a time, in order, and expect a database that has not been used by
 * an earlier run: the doctor spec closes a seeded visit, which can only happen
 * once. The API must allow the frontend's origin
 * (MEDICITY_CORS_ORIGINS=http://localhost:4173) and `vite preview` must proxy
 * /api to it (VITE_API_BASE_URL=http://localhost:8080).
 */
export default defineConfig({
  testDir: "./e2e",
  testMatch: "**/*.spec.ts",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : [["list"]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:4173",
    // The seed puts "today's" visits relative to the database clock, which is
    // UTC; the browser must agree about what today is.
    timezoneId: "UTC",
    locale: "en-IN",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    launchOptions: {
      ...(process.env.PW_CHROMIUM_PATH ? { executablePath: process.env.PW_CHROMIUM_PATH } : {}),
      args: ["--no-sandbox"],
    },
  },
  projects: [{ name: "chromium", use: { browserName: "chromium" } }],
});
