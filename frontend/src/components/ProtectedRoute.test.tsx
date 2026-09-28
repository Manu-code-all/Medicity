import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { AuthContext, type AuthContextValue, type Session } from "../auth/context";
import { LoginPage } from "../pages/LoginPage";
import { ProtectedRoute } from "./ProtectedRoute";

const MEERA: Session = { userId: "u1", fullName: "Meera Nair", role: "PATIENT" };

function DoctorsStub() {
  const { search } = useLocation();
  return <p>Doctors list{search}</p>;
}

/** A real sign-in round trip: the session appears only once the demo button is used. */
function renderAt(path: string) {
  function Harness() {
    const [session, setSession] = useState<Session | null>(null);
    const value = { session, login: async () => (setSession(MEERA), MEERA) } as unknown as AuthContextValue;
    return (
      <AuthContext.Provider value={value}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/register" element={<p>Create an account</p>} />
            <Route element={<ProtectedRoute />}>
              <Route path="/doctors" element={<DoctorsStub />} />
            </Route>
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>
    );
  }
  render(<Harness />);
}

describe("Finding a doctor needs sign-in", () => {
  it("sends a signed-out visitor to sign in, says why, then shows the doctors they picked", async () => {
    renderAt("/doctors?specialty=Cardiology&zone=chest");

    expect(screen.queryByText(/Doctors list/)).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Sign in as a patient" })).toBeInTheDocument();
    expect(screen.getByText("Sign in to see the Cardiology doctors available and book a time.")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /Try as Meera/ }));

    expect(await screen.findByText("Doctors list?specialty=Cardiology&zone=chest")).toBeInTheDocument();
  });

  it("names a searched doctor in the reason, and a new patient keeps the way back", () => {
    renderAt("/doctors?q=Rao");

    expect(screen.getByText("Sign in to see doctors matching “Rao” and book a time.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Create an account" })).toHaveAttribute("href", "/register");
  });
});
