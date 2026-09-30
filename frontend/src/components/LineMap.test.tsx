import { screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { NearbyStore } from "../api/types";
import { AuthContext, type AuthContextValue } from "../auth/context";
import { json, mockFetch } from "../test/fetchMock";
import { renderPage } from "../test/renderPage";
import { LineMap } from "./LineMap";

const HOURS = { opensAt: "08:00:00", closesAt: "22:00:00", open24h: false };
const STORES: NearbyStore[] = [
  { id: "s1", name: "Anna Nagar Medicals", phone: "1", addressLine: "x", city: "Chennai", latitude: 13.087, longitude: 80.21, distanceM: 420, holdHours: 3, openNow: true, ...HOURS },
  { id: "s2", name: "Kilpauk Pharmacy", phone: "2", addressLine: "y", city: "Chennai", latitude: 13.08, longitude: 80.23, distanceM: 1800, holdHours: 3, openNow: false, ...HOURS },
];

function renderMap(role: "PATIENT" | "DOCTOR" | "CHEMIST" | null) {
  const value = { session: role ? { userId: "u1", fullName: "Someone", role } : null } as AuthContextValue;
  return renderPage(
    <AuthContext.Provider value={value}>
      <LineMap />
    </AuthContext.Provider>,
  );
}

describe("LineMap", () => {
  beforeEach(() => {
    vi.stubGlobal("matchMedia", (query: string) => ({ matches: query.includes("reduce"), media: query }));
  });

  it.each(["PATIENT", "DOCTOR", "CHEMIST"] as const)("draws the chemists around a signed-in %s's own position", async (role) => {
    vi.stubGlobal("navigator", {
      geolocation: { getCurrentPosition: (ok: PositionCallback) => ok({ coords: { latitude: 13.0827, longitude: 80.2707 } } as GeolocationPosition) },
    });
    const calls = mockFetch(() => json(200, STORES));
    renderMap(role);

    expect(await screen.findByText("2 verified chemists within 3 km of you")).toBeInTheDocument();
    expect(screen.getAllByText("Anna Nagar Medicals").length).toBeGreaterThan(0);
    expect(screen.getAllByText("Open now").length).toBeGreaterThan(0);
    expect(screen.getAllByText("Closed now").length).toBeGreaterThan(0);
    expect(screen.getByText("You are here")).toBeInTheDocument();
    // It asks for the person's coordinates, not the demo area's.
    const asked = new URL(`http://x${calls[0]!.url}`).searchParams;
    expect(asked.get("lat")).toBe("13.0827");
    expect(asked.get("lng")).toBe("80.2707");
    expect(asked.get("radiusM")).toBe("3000");
    // Nobody has asked a real question, so no answers or prices are invented.
    expect(screen.queryByText(/omeprazole/)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Ask again" })).not.toBeInTheDocument();
  });

  it("falls back to the example, and says so, when location is refused", async () => {
    vi.stubGlobal("navigator", {
      geolocation: { getCurrentPosition: (_ok: PositionCallback, fail: PositionErrorCallback) => fail({ code: 1, PERMISSION_DENIED: 1 } as GeolocationPositionError) },
    });
    mockFetch(() => json(200, STORES));
    renderMap("PATIENT");

    expect(await screen.findByText(/Allow location in your browser to see the chemists around you/)).toBeInTheDocument();
    expect(screen.getByText(/chemists asked for 14 omeprazole capsules/)).toBeInTheDocument();
  });

  it("never asks for a position when nobody is signed in", () => {
    const getCurrentPosition = vi.fn();
    vi.stubGlobal("navigator", { geolocation: { getCurrentPosition } });
    mockFetch(() => json(200, STORES));
    renderMap(null);

    expect(getCurrentPosition).not.toHaveBeenCalled();
  });
});
