import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AuthContext, type AuthContextValue } from "../auth/context";
import { DesignSample } from "./DesignSample";

describe("DesignSample", () => {
  beforeEach(() => {
    vi.stubGlobal("matchMedia", (query: string) => ({ matches: query.includes("reduce"), media: query }));
  });

  it("opens on one promise and one action, then the line, the map, the guide and a close", () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <AuthContext.Provider value={{ session: null } as AuthContextValue}>
          <MemoryRouter>
            <DesignSample />
          </MemoryRouter>
        </AuthContext.Provider>
      </QueryClientProvider>,
    );

    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Find your doctor. Then your medicines nearby.");
    expect(screen.getByRole("combobox")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Heart" })).toHaveAttribute("href", "/doctors?specialty=Cardiology");
    expect(screen.getByRole("link", { name: /Not sure who to see/ })).toHaveAttribute("href", "#guide");

    for (const name of ["Book", "Visit", "Prescription", "Chemists nearby", "Pick up"]) {
      expect(screen.getByRole("heading", { level: 3, name })).toBeInTheDocument();
    }
    expect(screen.getByRole("heading", { name: "Every chemist within 3 km hears it at once." })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Chest" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /Your next prescription/ })).toBeInTheDocument();
    expect(document.querySelector('meta[name="robots"]')).toHaveAttribute("content", "noindex");
  });
});
