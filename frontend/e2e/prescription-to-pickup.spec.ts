import { expect, test } from "@playwright/test";
import { ACCOUNTS, newSession, OMEPRAZOLE_ID, signIn, uniqueTag } from "./helpers";

/**
 * One story in two tests that share state through the database: Dr. Rao closes
 * Arjun's visit and writes the prescription, then Arjun sends that prescription
 * to the chemists nearby, a chemist answers, and he reserves at that store.
 *
 * Arjun's visit comes from the demo seed ("Chest tightness when climbing
 * stairs", two hours ago), so it can only be closed once per database.
 */
test.describe.configure({ mode: "serial" });

const diagnosis = `E2E reflux ${uniqueTag()}`;

test("a doctor closes a visit and writes a prescription", async ({ page }) => {
  await signIn(page, ACCOUNTS.doctorRao);
  await expect(page).toHaveURL(/\/doctor$/);

  // The day's schedule lists the visit that is waiting to be closed.
  await page.getByRole("link", { name: /Arjun Mehta/ }).first().click();
  await expect(page.getByRole("heading", { name: "Arjun Mehta" })).toBeVisible();
  await expect(page.getByText("Chest tightness when climbing stairs")).toBeVisible();

  await page.getByRole("button", { name: "Patient was seen" }).click();
  await expect(page.getByRole("heading", { name: "Prescription" })).toBeVisible();

  await page.getByRole("button", { name: "Write prescription" }).click();
  await page.getByLabel("Diagnosis").fill(diagnosis);
  await page.getByLabel("Medicine").selectOption(OMEPRAZOLE_ID);
  await page.getByLabel("Dose").fill("20mg");
  await page.getByLabel("How often").fill("Once daily before breakfast");
  await page.getByLabel("Days").fill("14");
  await page.getByLabel("Qty").fill("14");
  await page.getByLabel(/Cheaper brand/).check();
  await page.getByRole("button", { name: "Issue prescription" }).click();

  // The issued prescription replaces the form.
  await expect(page.getByRole("heading", { name: diagnosis })).toBeVisible();
  await expect(page.locator(".rx-view")).toContainText("Omeprazole");
  await expect(page.getByRole("button", { name: "Correct prescription" })).toBeVisible();
});

test("the patient sends the prescription to the chemists and reserves at one", async ({ page, browser }) => {
  // The patient finds the prescription and asks every chemist nearby.
  await signIn(page, ACCOUNTS.patientArjun);
  await page.goto("/portal/prescriptions");
  const rx = page.locator("article.rx", { has: page.getByRole("heading", { name: diagnosis }) });
  await expect(rx).toBeVisible();
  await rx.getByRole("button", { name: "Ask chemists nearby" }).click();
  await rx.getByRole("button", { name: "Ask every chemist nearby" }).click();
  await expect(page).toHaveURL(/\/portal\/requests\/[0-9a-f-]+$/);
  const comparisonUrl = page.url();
  await expect(page.getByRole("heading", { name: "Sri Sai Medicals" })).toBeVisible();

  // A chemist, in their own browser session, sees the question and answers it.
  const chemistContext = await newSession(browser);
  try {
    const chemist = await chemistContext.newPage();
    await signIn(chemist, ACCOUNTS.chemistSriSai);
    await chemist.goto("/store/requests");
    await chemist.getByRole("link", { name: /Arjun/ }).first().click();
    await expect(chemist.getByRole("heading", { name: /Arjun.* is asking/ })).toBeVisible();
    await chemist.getByLabel(/^Price per /).fill("4.20");
    await chemist.getByRole("button", { name: "Send answer" }).click();
    await expect(chemist.getByRole("heading", { name: "Your answer" })).toBeVisible();
  } finally {
    await chemistContext.close();
  }

  // The answer is on the patient's comparison; reserving shows the pick-up code.
  await page.goto(comparisonUrl);
  const store = page.locator("li.compare__store", { has: page.getByRole("heading", { name: "Sri Sai Medicals" }) });
  await expect(store).toContainText("Has everything");
  await store.getByRole("button", { name: /^Reserve here/ }).click();

  const pickup = page.getByRole("region", { name: "Your reservation" });
  await expect(pickup).toContainText("Reserved at Sri Sai Medicals");
  await expect(pickup.locator(".pickup__code")).toHaveText(/^\d{6}$/);
});
