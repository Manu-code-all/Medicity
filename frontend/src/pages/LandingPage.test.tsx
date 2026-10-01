import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
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

  it("opens on one promise and one action, then shows the example chemists' answers on the map", () => {
    renderLanding();

    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Find your doctor. Then your medicines nearby.");
    // The search box is the main action; the body guide is the second door, one link away.
    expect(screen.getByRole("combobox")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Bones and joints" })).toHaveAttribute("href", "/doctors?specialty=Orthopaedics");
    expect(screen.getByRole("link", { name: /Not sure who to see/ })).toHaveAttribute("href", "#guide");
    expect(screen.getByRole("heading", { name: "Not sure who to see?" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Chest" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Every chemist within 3 km hears it at once." })).toBeInTheDocument();
    const main = screen.getByRole("main");
    expect(within(main).getAllByRole("link", { name: "Book a visit" })[0]).toHaveAttribute("href", "/doctors");

    expect(screen.getByText("5 chemists asked for 14 omeprazole capsules · 3 answered")).toBeInTheDocument();
    expect(screen.getAllByText("Lakshmi Medical Stores").length).toBeGreaterThan(0);
    expect(screen.getByText("Cheapest", { selector: ".lm-map__tag" })).toBeInTheDocument();
    // Signed out, the map says it is an example and names no neighbourhood or person.
    expect(screen.getByText("An example. Sign in to see the chemists around you.")).toBeInTheDocument();
    expect(screen.queryByText(/Indiranagar/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Meera/)).not.toBeInTheDocument();
  });

  it("puts the two pages in the capsule beside one way in, with no page links", async () => {
    renderLanding();

    const nav = screen.getByRole("navigation", { name: "Main" });
    expect(within(nav).getByRole("link", { name: "Sign in" })).toHaveAttribute("href", "/login");
    for (const gone of ["Find a doctor", "How it works", "For doctors", "For chemists", "Book a visit"]) {
      expect(within(nav).queryByRole("link", { name: gone })).not.toBeInTheDocument();
    }
    expect(within(nav).getByText("Find the right doctor, then your medicines nearby.")).toBeInTheDocument();
    expect(within(nav).getByText("From prescription to medicines in hand, near you.")).toBeInTheDocument();

    // Each page can be chosen; the current one is marked.
    await userEvent.click(within(nav).getByRole("button", { name: "Page 2 of 2" }));
    expect(within(nav).getByRole("button", { name: "Page 2 of 2" })).toHaveAttribute("aria-current", "true");
    expect(within(nav).getByRole("button", { name: "Page 1 of 2" })).toHaveAttribute("aria-current", "false");
  });

  it("no longer carries the demo links or the three lines section", () => {
    renderLanding();

    expect(screen.queryByText("Try the demo")).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /Three lines/ })).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /Your next prescription/ })).toBeInTheDocument();
  });

  it("walks the five stations and points each role to its own sign-in", () => {
    renderLanding();

    for (const station of ["Book", "Visit", "Prescription", "Chemists nearby", "Pick up"]) {
      expect(screen.getByRole("heading", { level: 3, name: station })).toBeInTheDocument();
    }
    expect(screen.getByText("Ask them all at once. Compare the answers.")).toBeInTheDocument();
    // Each role's own sign-in and registration sit in the footer.
    expect(screen.getByRole("link", { name: "Doctor sign in" })).toHaveAttribute("href", "/login/doctor");
    expect(screen.getByRole("link", { name: "Chemist sign in" })).toHaveAttribute("href", "/login/chemist");
    expect(screen.getByRole("link", { name: "Join as a doctor" })).toHaveAttribute("href", "/register/doctor");
    expect(screen.getByLabelText("Pick up code 482913")).toBeInTheDocument();
  });
});
