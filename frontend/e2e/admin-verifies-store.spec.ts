import { expect, test } from "@playwright/test";
import { ACCOUNTS, newSession, registerStore, signIn, uniqueTag } from "./helpers";

test("an admin verifies a newly registered store, and the chemist sees it verified", async ({ page, request, browser }) => {
  const tag = uniqueTag();
  const { email, store } = await registerStore(request, tag);

  // Before the check, the chemist is told the store is waiting.
  const chemistContext = await newSession(browser);
  try {
    const chemist = await chemistContext.newPage();
    await signIn(chemist, email);
    await expect(chemist).toHaveURL(/\/store$/);
    await expect(chemist.getByText("Waiting for verification.")).toBeVisible();

    // The admin finds it in the queue and verifies it.
    await signIn(page, ACCOUNTS.admin);
    await expect(page).toHaveURL(/\/admin\/stores/);
    const pending = page.locator("article.card", { has: page.getByRole("heading", { name: store.name }) });
    await expect(pending).toContainText(store.licenceNumber);
    await pending.getByRole("button", { name: "Licence checked: verify store" }).click();
    await expect(pending).toHaveCount(0);

    // The chemist's workspace now says verified.
    await chemist.reload();
    await expect(chemist.getByText(/^Verified/)).toBeVisible();
    await expect(chemist.getByText("Waiting for verification.")).toHaveCount(0);
  } finally {
    await chemistContext.close();
  }
});
