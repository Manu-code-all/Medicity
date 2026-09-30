import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, describe, expect, it } from "vitest";
import { actingFor } from "../../api/acting";
import type { FamilyMember } from "../../api/types";
import { AuthContext, type AuthContextValue } from "../../auth/context";
import { AuthProvider } from "../../auth/AuthContext";
import { ProtectedRoute } from "../../components/ProtectedRoute";
import { WorkspaceShell } from "../../components/WorkspaceShell";
import { json, mockFetch } from "../../test/fetchMock";
import { FamilySwitcher } from "./FamilySwitcher";
import { OverviewPage } from "./OverviewPage";
import { SESSION_KEY } from "../../auth/context";

const MEERA: FamilyMember = { patientId: "p1", fullName: "Meera Nair", relationship: null, self: true, age: 34, gender: "FEMALE", bloodGroup: "B+" };
const LALITHA: FamilyMember = { patientId: "p2", fullName: "Lalitha Nair", relationship: "PARENT", self: false, age: 63, gender: "FEMALE", bloodGroup: null };
const SUMMARY = { upcoming: 0, completed: 0, cancelled: 0, missed: 0, prescriptions: 0, nextVisit: null };

function client() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

afterEach(() => {
  actingFor.set(null);
  localStorage.clear();
});

describe("the patient portal's person switch", () => {
  it("opens the chosen person's overview, greets them by name, and asks for their records", async () => {
    const calls = mockFetch(({ url }) => json(200, url.includes("/family") ? [MEERA, LALITHA] : url.includes("/summary") ? SUMMARY : url.includes("/appointments") ? { content: [] } : []));
    const value = { session: { userId: "u1", fullName: "Meera Nair", role: "PATIENT" } } as AuthContextValue;
    render(
      <QueryClientProvider client={client()}>
        <AuthContext.Provider value={value}>
          {/* The visits page is where the person was when they switched. */}
          <MemoryRouter initialEntries={["/portal/visits"]}>
            <FamilySwitcher holderName="Meera Nair" />
            <Routes>
              <Route path="/portal" element={<OverviewPage />} />
              <Route path="/portal/visits" element={<p>Visits page</p>} />
            </Routes>
          </MemoryRouter>
        </AuthContext.Provider>
      </QueryClientProvider>,
    );

    expect(await screen.findByText("Visits page")).toBeInTheDocument();
    await userEvent.selectOptions(await screen.findByRole("combobox"), "p2");

    // No trip through the menu: the overview opens, in her name.
    expect(await screen.findByRole("heading", { level: 1, name: /, Lalitha$/ })).toBeInTheDocument();
    expect(screen.getByText("Here is everything about Lalitha's care, in one place.")).toBeInTheDocument();
    const summaryCalls = calls.filter((c) => c.url.includes("/summary"));
    expect(summaryCalls.at(-1)?.headers["X-Patient-Id"]).toBe("p2");

    await userEvent.selectOptions(screen.getByRole("combobox"), "p1");
    expect(await screen.findByRole("heading", { level: 1, name: /, Meera$/ })).toBeInTheDocument();
  });
});

describe("signing out", () => {
  it("does not send the next sign-in back to the page that was open", async () => {
    mockFetch(() => json(200, { unread: 0, items: [] }));
    localStorage.setItem(SESSION_KEY, JSON.stringify({ userId: "u1", fullName: "Meera Nair", role: "PATIENT" }));
    render(
      <QueryClientProvider client={client()}>
        <AuthProvider>
          <MemoryRouter initialEntries={["/portal/queue"]}>
            <Routes>
              <Route path="/" element={<p>Landing</p>} />
              <Route path="/login" element={<p>Sign in page</p>} />
              <Route element={<ProtectedRoute />}>
                <Route path="/portal" element={<WorkspaceShell label="Patient portal" who={null} stations={[]} />}>
                  <Route path="queue" element={<p>Walk-in tokens</p>} />
                </Route>
              </Route>
            </Routes>
          </MemoryRouter>
        </AuthProvider>
      </QueryClientProvider>,
    );

    expect(await screen.findByText("Walk-in tokens")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Sign out" }));

    expect(await screen.findByText("Landing")).toBeInTheDocument();
    expect(screen.queryByText("Sign in page")).not.toBeInTheDocument();
  });
});
