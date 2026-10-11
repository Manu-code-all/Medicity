import { expect, type APIRequestContext, type Browser, type Page } from "@playwright/test";

/**
 * A second, separate browser session (its own storage, so its own sign-in).
 * `browser.newContext()` does not inherit the config's `use` options, so the
 * ones the pages depend on are repeated here.
 */
export function newSession(browser: Browser) {
  return browser.newContext({
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:4173",
    timezoneId: "UTC",
    locale: "en-IN",
  });
}

/** Every demo account uses this password (backend seed R__demo_data.sql). */
export const DEMO_PASSWORD = "demo-password-2026";

export const ACCOUNTS = {
  /** Has a started visit with Dr. Rao waiting to be closed. */
  patientArjun: "arjun@medicity.demo",
  patientMeera: "patient@medicity.demo",
  doctorRao: "dr.rao@medicity.demo",
  chemistSriSai: "chemist@medicity.demo",
  admin: "admin@medicity.demo",
} as const;

/** Omeprazole 20mg in the seeded catalogue. */
export const OMEPRAZOLE_ID = "cccccccc-3333-4333-8333-cccccccccc06";

/** Short and unique per run, to find this run's rows among the seeded ones. */
export function uniqueTag(): string {
  return Date.now().toString(36) + Math.floor(Math.random() * 1296).toString(36);
}

/** Signs in through the real form with email and password and waits for the role's home page. */
export async function signIn(page: Page, email: string, password = DEMO_PASSWORD): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

/** Registers a chemist and an unverified store through the API; the store waits for an admin. */
export async function registerStore(request: APIRequestContext, tag: string) {
  const email = `store-${tag}@e2e.test`;
  const store = {
    name: `E2E Pharmacy ${tag}`,
    licenceNumber: `KA-E2E-${tag.toUpperCase()}`,
    phone: "+919876511111",
    addressLine: "1 Test Street, Indiranagar",
    city: "Bengaluru",
    latitude: 12.9725,
    longitude: 77.6415,
    opensAt: "09:00",
    closesAt: "21:00",
    open24h: false,
    holdHours: 3,
  };
  const response = await request.post("/api/v1/auth/register/chemist", {
    data: { email, password: DEMO_PASSWORD, fullName: `Owner ${tag}`, store, acceptedTerms: true },
  });
  expect(response.ok(), await response.text()).toBe(true);
  return { email, store };
}
