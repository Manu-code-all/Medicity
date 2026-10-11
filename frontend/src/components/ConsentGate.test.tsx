import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import type { ConsentStatus } from "../api/types";
import { AuthContext, type AuthContextValue } from "../auth/context";
import { json, mockFetch } from "../test/fetchMock";
import { ConsentGate } from "./ConsentGate";

const OUT_OF_DATE: ConsentStatus = {
  pending: true,
  items: [
    { type: "TERMS_OF_SERVICE", currentVersion: "v2", acceptedVersion: "v1", acceptedAt: "2026-01-01T00:00:00Z", upToDate: false },
    { type: "PRIVACY_NOTICE", currentVersion: "v2", acceptedVersion: "v2", acceptedAt: "2026-02-01T00:00:00Z", upToDate: true },
    { type: "HEALTH_DATA_PROCESSING", currentVersion: "v2", acceptedVersion: null, acceptedAt: null, upToDate: false },
  ],
};
const UP_TO_DATE: ConsentStatus = { pending: false, items: OUT_OF_DATE.items.map((i) => ({ ...i, upToDate: true })) };

function renderGate(path = "/portal", signedIn = true, logout = vi.fn()) {
  const value = {
    session: signedIn ? { userId: "u1", fullName: "Meera", role: "PATIENT" } : null,
    logout,
  } as unknown as AuthContextValue;
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AuthContext.Provider value={value}>
        <MemoryRouter initialEntries={[path]}>
          <ConsentGate />
        </MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>,
  );
  return logout;
}

describe("ConsentGate", () => {
  it("asks for each outstanding text on its own, and only those", async () => {
    mockFetch(() => json(200, OUT_OF_DATE));
    renderGate();

    expect(await screen.findByRole("dialog", { name: "Please review what you agree to" })).toBeInTheDocument();
    expect(screen.getAllByRole("checkbox")).toHaveLength(2);   // the privacy notice is up to date
    expect(screen.getByLabelText(/updated terms of service/)).not.toBeChecked();
    expect(screen.getByLabelText(/keeping and using my health information/)).not.toBeChecked();
    expect(screen.getByRole("button", { name: "I agree" })).toBeDisabled();
  });

  it("agreeing needs every box ticked, sends the outstanding types, and lets the person on", async () => {
    const calls = mockFetch(({ method }) => json(200, method === "POST" ? UP_TO_DATE : OUT_OF_DATE));
    renderGate();
    await screen.findByRole("dialog");

    await userEvent.click(screen.getByLabelText(/updated terms of service/));
    expect(screen.getByRole("button", { name: "I agree" })).toBeDisabled();
    await userEvent.click(screen.getByLabelText(/keeping and using my health information/));
    await userEvent.click(screen.getByRole("button", { name: "I agree" }));

    await vi.waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    const post = calls.find((c) => c.method === "POST");
    expect(post?.url).toContain("/api/v1/consents/me");
    expect(post?.body).toEqual({ types: ["TERMS_OF_SERVICE", "HEALTH_DATA_PROCESSING"] });
  });

  it("can be declined by signing out", async () => {
    mockFetch(() => json(200, OUT_OF_DATE));
    const logout = renderGate();
    await screen.findByRole("dialog");

    await userEvent.click(screen.getByRole("button", { name: "Sign out" }));
    expect(logout).toHaveBeenCalledTimes(1);
  });

  it("shows nothing when everything is current, when signed out, or over the legal pages themselves", async () => {
    const calls = mockFetch(() => json(200, UP_TO_DATE));
    renderGate();
    await vi.waitFor(() => expect(calls.length).toBeGreaterThan(0));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("does not ask a signed-out visitor, or cover the privacy notice it links to", async () => {
    const calls = mockFetch(() => json(200, OUT_OF_DATE));
    renderGate("/portal", false);
    expect(calls).toHaveLength(0);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("stays out of the way on the legal pages", async () => {
    const calls = mockFetch(() => json(200, OUT_OF_DATE));
    renderGate("/privacy");
    await vi.waitFor(() => expect(calls.length).toBeGreaterThan(0));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});
