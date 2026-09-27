import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import { AuthContext, type AuthContextValue, type Session } from "../auth/context";
import { LoginPage } from "./LoginPage";

function renderAt(path: string, login: AuthContextValue["login"]) {
  const value = { session: null, login } as unknown as AuthContextValue;
  render(
    <AuthContext.Provider value={value}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/login/doctor" element={<LoginPage role="doctor" />} />
          <Route path="/login/chemist" element={<LoginPage role="chemist" />} />
          <Route path="/portal" element={<p>Patient portal</p>} />
          <Route path="/doctor" element={<p>Doctor workspace</p>} />
          <Route path="/store" element={<p>Store workspace</p>} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

const as = (role: Session["role"]): Session => ({ userId: "u1", fullName: "Someone", role });

describe("LoginPage", () => {
  it("signs in with the role's demo account in one click and lands in that role's home", async () => {
    const login = vi.fn(async () => as("DOCTOR"));
    renderAt("/login/doctor", login);

    expect(screen.getByRole("heading", { name: "Sign in as a doctor" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /Try as Dr. Anjali Rao/ }));

    expect(await screen.findByText("Doctor workspace")).toBeInTheDocument();
    expect(login).toHaveBeenCalledWith("dr.rao@medicity.demo", "demo-password-2026");
  });

  it("switches role from the tabs, and each page offers its own next step", async () => {
    renderAt("/login", vi.fn());

    expect(screen.getByRole("link", { name: "Patient" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Create an account" })).toHaveAttribute("href", "/register");

    await userEvent.click(screen.getByRole("link", { name: "Chemist" }));

    expect(screen.getByRole("heading", { name: "Sign in as a chemist" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Try as Ravi Kumar/ })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Register your store" })).toHaveAttribute("href", "/register/store");
  });

  it("signs in with typed details, and shows the server's message when they are wrong", async () => {
    const login = vi
      .fn()
      .mockRejectedValueOnce(new ApiError(401, { detail: "Email or password is incorrect" }))
      .mockResolvedValueOnce(as("PATIENT"));
    renderAt("/login", login);

    await userEvent.type(screen.getByLabelText("Email"), "meera@example.com");
    await userEvent.type(screen.getByLabelText("Password"), "wrong");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Email or password is incorrect");

    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));
    expect(await screen.findByText("Patient portal")).toBeInTheDocument();
    expect(login).toHaveBeenLastCalledWith("meera@example.com", "wrong");
  });
});
