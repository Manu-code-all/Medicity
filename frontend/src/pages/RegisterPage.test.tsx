import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import { AuthContext, type AuthContextValue } from "../auth/context";
import { RegisterPage } from "./RegisterPage";

function renderPage(register: AuthContextValue["register"]) {
  const value = { session: null, register } as unknown as AuthContextValue;
  render(
    <AuthContext.Provider value={value}>
      <MemoryRouter initialEntries={["/register"]}>
        <Routes>
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/welcome" element={<p>Welcome page</p>} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

async function fill() {
  await userEvent.type(screen.getByLabelText("Full name"), "Meera Nair");
  await userEvent.type(screen.getByLabelText("Email"), "meera@example.com");
  await userEvent.type(screen.getByLabelText("Password"), "a-long-enough-password");
  await userEvent.type(screen.getByLabelText("Date of birth"), "1994-08-12");
}

describe("RegisterPage", () => {
  it("has two unticked agreements, one for the terms and one for health information, and links to the legal pages", () => {
    renderPage(vi.fn());

    expect(screen.getByLabelText(/I agree to the Terms of service/)).not.toBeChecked();
    expect(screen.getByLabelText(/keeping and using my health information/)).not.toBeChecked();
    expect(screen.getAllByRole("link", { name: "Privacy notice" }).length).toBeGreaterThan(0);
    expect(screen.getAllByRole("link", { name: "Terms of service" })[0]).toHaveAttribute("href", "/terms");
    expect(screen.getByRole("link", { name: "Medical disclaimer" })).toHaveAttribute("href", "/disclaimer");
  });

  it("sends both agreements with the sign-up", async () => {
    const register = vi.fn(async () => {});
    renderPage(register);

    await fill();
    await userEvent.click(screen.getByLabelText(/I agree to the Terms of service/));
    await userEvent.click(screen.getByLabelText(/keeping and using my health information/));
    await userEvent.click(screen.getByRole("button", { name: "Create account" }));

    expect(await screen.findByText("Welcome page")).toBeInTheDocument();
    expect(register).toHaveBeenCalledWith(
      expect.objectContaining({ email: "meera@example.com", acceptedTerms: true, acceptedHealthData: true }),
    );
  });

  it("does not submit until both are ticked", async () => {
    const register = vi.fn(async () => {});
    renderPage(register);

    await fill();
    await userEvent.click(screen.getByLabelText(/I agree to the Terms of service/));
    await userEvent.click(screen.getByRole("button", { name: "Create account" }));

    expect(register).not.toHaveBeenCalled();
  });

  it("shows the server's reason when an agreement is refused", async () => {
    const register = vi.fn().mockRejectedValue(
      new ApiError(400, { code: "VALIDATION_FAILED", fieldErrors: { acceptedHealthData: "We need your agreement to keep your health information" } }),
    );
    renderPage(register);

    await fill();
    await userEvent.click(screen.getByLabelText(/I agree to the Terms of service/));
    await userEvent.click(screen.getByLabelText(/keeping and using my health information/));
    await userEvent.click(screen.getByRole("button", { name: "Create account" }));

    expect(await screen.findByText(/We need your agreement/)).toBeInTheDocument();
  });
});
