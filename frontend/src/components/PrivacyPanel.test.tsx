import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AuthContext, type AuthContextValue } from "../auth/context";
import { json, mockFetch } from "../test/fetchMock";
import { PrivacyPanel } from "./PrivacyPanel";

function renderPanel(logout = vi.fn()) {
  const value = { session: { userId: "u1", fullName: "Meera Nair", role: "PATIENT" }, logout } as unknown as AuthContextValue;
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { mutations: { retry: false } } })}>
      <AuthContext.Provider value={value}>
        <MemoryRouter initialEntries={["/portal/profile"]}>
          <Routes>
            <Route path="/portal/profile" element={<PrivacyPanel />} />
            <Route path="/delete-account" element={<p>Deletion page</p>} />
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>,
  );
  return logout;
}

afterEach(() => vi.restoreAllMocks());

describe("PrivacyPanel", () => {
  it("downloads the patient's data as a JSON file", async () => {
    const calls = mockFetch(() => json(200, { account: { email: "meera@example.com" } }));
    const createObjectURL = vi.fn(() => "blob:data");
    vi.stubGlobal("URL", Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() }));
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    renderPanel();

    await userEvent.click(screen.getByRole("button", { name: "Download my data" }));

    await waitFor(() => expect(click).toHaveBeenCalledTimes(1));
    expect(calls[0]?.url).toContain("/api/v1/patients/me/export");
    const blob = (createObjectURL.mock.calls[0] as unknown as [Blob])[0];
    expect(blob.type).toBe("application/json");
    const text = await new Promise<string>((resolve) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.readAsText(blob);
    });
    expect(text).toContain("meera@example.com");
  });

  it("shows a clear warning before deleting, and nothing is sent until the password is typed and confirmed", async () => {
    const calls = mockFetch(() => json(200, {}));
    renderPanel();

    await userEvent.click(screen.getByRole("button", { name: "Delete my account" }));

    expect(screen.getByText("This cannot be undone.")).toBeInTheDocument();
    expect(screen.getByText(/Records your doctors must keep by law/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Delete my account permanently" })).toBeDisabled();
    expect(calls).toHaveLength(0);

    await userEvent.click(screen.getByRole("button", { name: "Keep my account" }));
    expect(screen.queryByText("This cannot be undone.")).not.toBeInTheDocument();
  });

  it("a wrong password keeps the account, says so, and signs nobody out", async () => {
    mockFetch(() => json(422, { code: "INCORRECT_PASSWORD", detail: "That password is not correct" }));
    const logout = renderPanel();

    await userEvent.click(screen.getByRole("button", { name: "Delete my account" }));
    await userEvent.type(screen.getByLabelText("Type your password to confirm"), "nope");
    await userEvent.click(screen.getByRole("button", { name: "Delete my account permanently" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("That password is not correct");
    expect(logout).not.toHaveBeenCalled();
  });

  it("deleting sends the password, signs out, and lands on the confirmation page", async () => {
    const calls = mockFetch(() => json(200, { patientsAnonymised: 1 }));
    const logout = renderPanel();

    await userEvent.click(screen.getByRole("button", { name: "Delete my account" }));
    await userEvent.type(screen.getByLabelText("Type your password to confirm"), "my-password");
    await userEvent.click(screen.getByRole("button", { name: "Delete my account permanently" }));

    expect(await screen.findByText("Deletion page")).toBeInTheDocument();
    expect(logout).toHaveBeenCalledTimes(1);
    expect(calls[0]).toMatchObject({
      method: "POST",
      body: { password: "my-password" },
    });
    expect(calls[0]?.url).toContain("/api/v1/patients/me/delete-account");
  });
});
