import { expect, test } from "@playwright/test";
import { ACCOUNTS, signIn, uniqueTag } from "./helpers";

test("a patient books a slot and sees it among their upcoming visits", async ({ page }) => {
  const reason = `E2E booking ${uniqueTag()}`;

  await signIn(page, ACCOUNTS.patientMeera);
  await expect(page).toHaveURL(/\/portal/);

  // Find a doctor in the directory and open the booking page.
  await page.goto("/doctors");
  const card = page.locator("li.dcard", { has: page.getByRole("heading", { name: "Dr. Farah Khan" }) });
  await card.getByRole("link", { name: /^(See all times|Book)$/ }).click();
  await expect(page.getByRole("heading", { name: "Book a visit" })).toBeVisible();

  // Pick the first free time on the first day and say why.
  const firstTime = page.locator(".timegrid button").first();
  const slotLabel = await firstTime.getAttribute("aria-label");
  expect(slotLabel).toBeTruthy();
  await firstTime.click();
  await expect(firstTime).toHaveAttribute("aria-pressed", "true");
  await page.getByLabel("What brings you in?").fill(reason);
  await page.getByRole("button", { name: /^Confirm / }).click();

  await expect(page.getByRole("status").filter({ hasText: "Appointment confirmed." })).toBeVisible();

  // The slot is taken for the next person: it is no longer offered.
  await expect(page.locator(`.timegrid button[aria-label="${slotLabel}"]`)).toHaveCount(0);

  // And the visit is in the patient's portal.
  await page.getByRole("link", { name: "See it in your portal" }).click();
  await expect(page).toHaveURL(/\/portal\/visits/);
  const visit = page.locator("li", { hasText: reason });
  await expect(visit).toBeVisible();
  await expect(visit).toContainText("Dr. Farah Khan");
});
