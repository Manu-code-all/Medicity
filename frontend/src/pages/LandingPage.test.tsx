import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AuthContext, type AuthContextValue } from "../auth/context";
import { LandingPage } from "./LandingPage";

function renderLanding() {
  const value = { session: null } as AuthContextValue;
  render(
    <QueryClientProvider client={new QueryClient()}>
      <AuthContext.Provider value={value}>
        <MemoryRouter>
          <LandingPage />
        </MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>,
  );
}

describe("LandingPage", () => {
  beforeEach(() => {
    // Reduced motion shows the map's final state at once.
    vi.stubGlobal("matchMedia", (query: string) => ({ matches: query.includes("reduce"), media: query }));
  });

  it("leads with the two doors, then shows the demo chemists' real answers on the map", () => {
    renderLanding();

    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Find the right doctor, then your medicines nearby.");
    expect(screen.getByRole("heading", { name: "Not sure who to consult?" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Know who you are looking for?" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Chest" })).toBeInTheDocument();
    expect(screen.getByRole("combobox")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Bones and joints" })).toHaveAttribute("href", "/doctors?specialty=Orthopaedics");
    expect(screen.getByRole("heading", { name: "From prescription to medicines in hand, near you." })).toBeInTheDocument();
    const main = screen.getByRole("main");
    expect(within(main).getAllByRole("link", { name: "Book a visit" })[0]).toHaveAttribute("href", "/doctors");

    expect(screen.getByText("5 chemists asked for 14 omeprazole capsules · 3 answered")).toBeInTheDocument();
    expect(screen.getAllByText("Lakshmi Medical Stores").length).toBeGreaterThan(0);
    expect(screen.getByText("Cheapest", { selector: ".lm-map__tag" })).toBeInTheDocument();
  });

  it("keeps the top navigation to finding a doctor and signing in", () => {
    renderLanding();

    const nav = screen.getByRole("navigation", { name: "Main" });
    expect(within(nav).getByRole("link", { name: "Find a doctor" })).toHaveAttribute("href", "/doctors");
    expect(within(nav).getByRole("link", { name: "Sign in" })).toHaveAttribute("href", "/login");
    for (const gone of ["For doctors", "For chemists", "Book a visit"]) {
      expect(within(nav).queryByRole("link", { name: gone })).not.toBeInTheDocument();
    }
  });

  it("walks the five stations and points each role to its own sign-in", () => {
    renderLanding();

    for (const station of ["Book", "Visit", "Prescription", "Chemists nearby", "Pick up"]) {
      expect(screen.getByRole("heading", { level: 3, name: station })).toBeInTheDocument();
    }
    expect(screen.getByText("Ask them all at once, compare the answers")).toBeInTheDocument();
    for (const link of screen.getAllByRole("link", { name: /Doctor sign in/ })) expect(link).toHaveAttribute("href", "/login/doctor");
    for (const link of screen.getAllByRole("link", { name: /Chemist sign in/ })) expect(link).toHaveAttribute("href", "/login/chemist");
    expect(screen.getByLabelText("Pick up code 482913")).toBeInTheDocument();
  });
});
