import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { json, mockFetch } from "../../test/fetchMock";
import { renderLayout } from "../../test/session";
import { PharmacistsPage } from "./PharmacistsPage";

const ADMIN = { userId: "a1", fullName: "Ops Admin", role: "ADMIN" as const };
const EXISTING = { id: "p1", email: "old@medicity.test", fullName: "Old Hand", enabled: true, createdAt: "2030-01-01T09:00:00Z" };

describe("PharmacistsPage", () => {
  it("creates a pharmacist and shows the list again", async () => {
    let created = false;
    const calls = mockFetch(({ method }) => {
      if (method === "POST") {
        created = true;
        return json(201, { ...EXISTING, id: "p2", email: "asha@medicity.test", fullName: "Asha Menon" });
      }
      return json(200, created ? [EXISTING, { ...EXISTING, id: "p2", email: "asha@medicity.test", fullName: "Asha Menon" }] : [EXISTING]);
    });
    renderLayout(<PharmacistsPage />, "/admin/pharmacists", ADMIN, undefined);

    expect(await screen.findByText("Old Hand")).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("Full name"), "Asha Menon");
    await userEvent.type(screen.getByLabelText("Email"), "asha@medicity.test");
    await userEvent.type(screen.getByLabelText(/Temporary password/), "temporary-pass-1");
    await userEvent.click(screen.getByRole("button", { name: "Create pharmacist" }));

    expect(await screen.findByText("Created Asha Menon.")).toBeInTheDocument();
    expect(calls.find((c) => c.method === "POST")).toMatchObject({
      url: "/api/v1/admin/pharmacists",
      body: { email: "asha@medicity.test", fullName: "Asha Menon", temporaryPassword: "temporary-pass-1" },
    });
    expect(await screen.findByText(/asha@medicity.test · added/)).toBeInTheDocument();
  });

  it("shows the server's message when the email is taken", async () => {
    mockFetch(({ method }) =>
      method === "POST"
        ? json(409, { code: "EMAIL_TAKEN", title: "Conflict", status: 409, detail: "An account with that email already exists" })
        : json(200, []),
    );
    renderLayout(<PharmacistsPage />, "/admin/pharmacists", ADMIN, undefined);

    await userEvent.type(await screen.findByLabelText("Full name"), "Asha Menon");
    await userEvent.type(screen.getByLabelText("Email"), "old@medicity.test");
    await userEvent.type(screen.getByLabelText(/Temporary password/), "temporary-pass-1");
    await userEvent.click(screen.getByRole("button", { name: "Create pharmacist" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("already exists");
  });

  it("is for administrators only", () => {
    mockFetch(() => json(200, []));
    renderLayout(<PharmacistsPage />, "/admin/pharmacists", { userId: "u", fullName: "Asha", role: "PHARMACIST" }, undefined);
    expect(screen.getByRole("heading", { name: "For administrators" })).toBeInTheDocument();
  });
});
