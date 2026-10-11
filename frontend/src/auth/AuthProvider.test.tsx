import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { json, mockFetch } from "../test/fetchMock";
import { AuthProvider } from "./AuthContext";
import { SESSION_KEY, useAuth } from "./context";

const PAIR = { accessToken: "acc", refreshToken: "ref", userId: "u1", fullName: "Meera Nair", role: "PATIENT" };

function Probe() {
  const { session, loginWithEmailCode, loginWithCode, logout } = useAuth();
  return (
    <div>
      <p>{session ? `Signed in as ${session.fullName} (${session.role})` : "Signed out"}</p>
      <button onClick={() => void loginWithEmailCode("meera@example.com", "123456")}>email code</button>
      <button onClick={() => void loginWithCode("98765 00001", "654321")}>sms code</button>
      <button onClick={logout}>out</button>
    </div>
  );
}

function renderProvider() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <AuthProvider>
        <Probe />
      </AuthProvider>
    </QueryClientProvider>,
  );
}

describe("AuthProvider", () => {
  it("signs in with an emailed code, keeps the tokens and shows the session", async () => {
    const calls = mockFetch(() => json(200, PAIR));
    renderProvider();
    expect(screen.getByText("Signed out")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "email code" }));

    expect(await screen.findByText("Signed in as Meera Nair (PATIENT)")).toBeInTheDocument();
    expect(calls[0]).toMatchObject({
      url: "/api/v1/auth/otp/email/verify",
      body: { email: "meera@example.com", code: "123456" },
    });
    expect(localStorage.getItem(SESSION_KEY)).toContain("Meera Nair");
    expect(JSON.stringify(Object.values(localStorage))).toContain("acc");
  });

  it("signs in with an SMS code through the phone endpoint", async () => {
    const calls = mockFetch(() => json(200, PAIR));
    renderProvider();
    await userEvent.click(screen.getByRole("button", { name: "sms code" }));
    expect(await screen.findByText(/Signed in as Meera Nair/)).toBeInTheDocument();
    expect(calls[0]).toMatchObject({ url: "/api/v1/auth/otp/verify", body: { phone: "98765 00001", code: "654321" } });
  });

  it("restores the session on load and forgets everything on sign-out", async () => {
    localStorage.setItem(SESSION_KEY, JSON.stringify({ userId: "u1", fullName: "Meera Nair", role: "PATIENT" }));
    mockFetch(() => json(204, null));
    renderProvider();
    expect(screen.getByText("Signed in as Meera Nair (PATIENT)")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "out" }));

    expect(screen.getByText("Signed out")).toBeInTheDocument();
    expect(localStorage.getItem(SESSION_KEY)).toBeNull();
  });

  it("drops a corrupted stored session instead of crashing", () => {
    localStorage.setItem(SESSION_KEY, "{not json");
    renderProvider();
    expect(screen.getByText("Signed out")).toBeInTheDocument();
    expect(localStorage.getItem(SESSION_KEY)).toBeNull();
  });
});
