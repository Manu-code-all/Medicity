import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import { json, mockFetch } from "../test/fetchMock";
import { AuthContext, type AuthContextValue, type Session } from "../auth/context";
import { LoginPage } from "./LoginPage";

function renderAt(
  path: string,
  login: AuthContextValue["login"],
  loginWithCode: AuthContextValue["loginWithCode"] = vi.fn(),
  loginWithEmailCode: AuthContextValue["loginWithEmailCode"] = vi.fn(),
) {
  const value = { session: null, login, loginWithCode, loginWithEmailCode } as unknown as AuthContextValue;
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

    await userEvent.click(screen.getByRole("tab", { name: "Email" }));
    await userEvent.type(screen.getByLabelText("Email"), "meera@example.com");
    await userEvent.type(screen.getByLabelText("Password"), "wrong");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Email or password is incorrect");

    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));
    expect(await screen.findByText("Patient portal")).toBeInTheDocument();
    expect(login).toHaveBeenLastCalledWith("meera@example.com", "wrong");
  });

  it("signs in with a mobile number: the demo account's code is shown, filled in and verified", async () => {
    const calls = mockFetch(() =>
      json(200, { delivery: "DEMO", sentTo: "+91 98765 •••01", demoCode: "482913", expiresInSeconds: 300 }),
    );
    const loginWithCode = vi.fn(async () => as("DOCTOR"));
    renderAt("/login/doctor", vi.fn(), loginWithCode);

    expect(screen.getByRole("tab", { name: "Mobile number" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByText("98765 00001")).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("Mobile number"), "98765 00001");
    await userEvent.click(screen.getByRole("button", { name: "Send code" }));

    expect(await screen.findByText("482913")).toBeInTheDocument();
    expect(calls[0]).toMatchObject({ url: "/api/v1/auth/otp/send", body: { phone: "98765 00001" } });
    const verify = screen.getByRole("button", { name: "Verify and sign in" });
    expect(verify).toBeDisabled();

    await userEvent.click(screen.getByRole("button", { name: "Fill it in" }));
    await userEvent.click(verify);

    expect(await screen.findByText("Doctor workspace")).toBeInTheDocument();
    expect(loginWithCode).toHaveBeenCalledWith("98765 00001", "482913");
  });

  it("says plainly when codes by SMS are not switched on, and offers email instead", async () => {
    mockFetch(() => json(200, { delivery: "UNAVAILABLE", sentTo: null, demoCode: null, expiresInSeconds: 0 }));
    renderAt("/login", vi.fn());

    await userEvent.type(screen.getByLabelText("Mobile number"), "99887 76655");
    await userEvent.click(screen.getByRole("button", { name: "Send code" }));

    expect(await screen.findByText(/Codes by SMS are not switched on yet/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Use email instead" }));
    expect(screen.getByLabelText("Email")).toBeInTheDocument();
  });

  it("shows the server's answer to a wrong code and clears the box", async () => {
    mockFetch(() => json(200, { delivery: "SMS", sentTo: "+91 99887 •••55", demoCode: null, expiresInSeconds: 300 }));
    const loginWithCode = vi.fn().mockRejectedValue(
      new ApiError(401, { code: "WRONG_CODE", detail: "That code is wrong or has expired. Ask for a new one." }),
    );
    renderAt("/login", vi.fn(), loginWithCode);

    await userEvent.type(screen.getByLabelText("Mobile number"), "99887 76655");
    await userEvent.click(screen.getByRole("button", { name: "Send code" }));
    expect(await screen.findByText(/If \+91 99887 •••55 has an account, a code is on its way/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Send again in/ })).toBeDisabled();

    await userEvent.type(screen.getByLabelText("6 digit code"), "111111");
    await userEvent.click(screen.getByRole("button", { name: "Verify and sign in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("wrong or has expired");
    expect(screen.getByLabelText("6 digit code")).toHaveValue("");
  });

  describe("with a code sent to the email address", () => {
    async function openEmailCode() {
      await userEvent.click(screen.getByRole("tab", { name: "Email" }));
      await userEvent.click(screen.getByRole("button", { name: "Email me a code instead" }));
    }

    it("asks for a code by email, then signs in with it", async () => {
      const calls = mockFetch(() =>
        json(200, { delivery: "EMAIL", sentTo: "m•••@example.com", demoCode: null, expiresInSeconds: 300 }),
      );
      const loginWithEmailCode = vi.fn(async () => as("PATIENT"));
      renderAt("/login", vi.fn(), vi.fn(), loginWithEmailCode);

      await openEmailCode();
      await userEvent.type(screen.getByLabelText("Email"), "  meera@example.com ");
      await userEvent.click(screen.getByRole("button", { name: "Email me a code" }));

      expect(await screen.findByText(/If m•••@example.com has an account, a code is on its way/)).toBeInTheDocument();
      // Trimmed before it is sent, and it is the email endpoint, not the SMS one.
      expect(calls[0]).toMatchObject({ url: "/api/v1/auth/otp/email/send", body: { email: "meera@example.com" } });
      expect(screen.getByRole("button", { name: /Send again in/ })).toBeDisabled();
      expect(screen.getByRole("button", { name: "Change email" })).toBeInTheDocument();

      await userEvent.type(screen.getByLabelText("6 digit code"), "482913");
      await userEvent.click(screen.getByRole("button", { name: "Verify and sign in" }));

      expect(await screen.findByText("Patient portal")).toBeInTheDocument();
      expect(loginWithEmailCode).toHaveBeenCalledWith("meera@example.com", "482913");
    });

    it("shows a demo account's code on screen instead of saying it was emailed", async () => {
      mockFetch(() => json(200, { delivery: "DEMO", sentTo: "p•••@medicity.demo", demoCode: "110022", expiresInSeconds: 300 }));
      const loginWithEmailCode = vi.fn(async () => as("PATIENT"));
      renderAt("/login", vi.fn(), vi.fn(), loginWithEmailCode);

      await openEmailCode();
      expect(screen.getByText("patient@medicity.demo")).toBeInTheDocument();
      await userEvent.type(screen.getByLabelText("Email"), "patient@medicity.demo");
      await userEvent.click(screen.getByRole("button", { name: "Email me a code" }));

      expect(await screen.findByText(/Demo account, so no email/)).toBeInTheDocument();
      await userEvent.click(screen.getByRole("button", { name: "Fill it in" }));
      await userEvent.click(screen.getByRole("button", { name: "Verify and sign in" }));

      expect(await screen.findByText("Patient portal")).toBeInTheDocument();
      expect(loginWithEmailCode).toHaveBeenCalledWith("patient@medicity.demo", "110022");
    });

    it("says plainly when codes by email are not switched on, and goes back to the password", async () => {
      mockFetch(() => json(200, { delivery: "UNAVAILABLE", sentTo: null, demoCode: null, expiresInSeconds: 0 }));
      renderAt("/login", vi.fn());

      await openEmailCode();
      await userEvent.type(screen.getByLabelText("Email"), "someone@example.com");
      await userEvent.click(screen.getByRole("button", { name: "Email me a code" }));

      expect(await screen.findByText(/Codes by email are not switched on yet/)).toBeInTheDocument();
      await userEvent.click(screen.getByRole("button", { name: "Use password instead" }));
      expect(screen.getByLabelText("Password")).toBeInTheDocument();
    });

    it("shows the server's answer to a wrong code and clears the box", async () => {
      mockFetch(() => json(200, { delivery: "EMAIL", sentTo: "s•••@example.com", demoCode: null, expiresInSeconds: 300 }));
      const loginWithEmailCode = vi.fn().mockRejectedValue(
        new ApiError(401, { code: "WRONG_CODE", detail: "That code is wrong or has expired. Ask for a new one." }),
      );
      renderAt("/login", vi.fn(), vi.fn(), loginWithEmailCode);

      await openEmailCode();
      await userEvent.type(screen.getByLabelText("Email"), "someone@example.com");
      await userEvent.click(screen.getByRole("button", { name: "Email me a code" }));
      await userEvent.type(await screen.findByLabelText("6 digit code"), "111111");
      await userEvent.click(screen.getByRole("button", { name: "Verify and sign in" }));

      expect(await screen.findByRole("alert")).toHaveTextContent("wrong or has expired");
      expect(screen.getByLabelText("6 digit code")).toHaveValue("");
    });

    it("the password form is still the default under Email, and Change email returns to the address box", async () => {
      mockFetch(() => json(200, { delivery: "EMAIL", sentTo: "s•••@example.com", demoCode: null, expiresInSeconds: 300 }));
      renderAt("/login", vi.fn());

      await userEvent.click(screen.getByRole("tab", { name: "Email" }));
      expect(screen.getByLabelText("Password")).toBeInTheDocument();

      await userEvent.click(screen.getByRole("button", { name: "Email me a code instead" }));
      await userEvent.type(screen.getByLabelText("Email"), "someone@example.com");
      await userEvent.click(screen.getByRole("button", { name: "Email me a code" }));
      await userEvent.click(await screen.findByRole("button", { name: "Change email" }));

      expect(screen.getByLabelText("Email")).toHaveValue("someone@example.com");
    });
  });
});
