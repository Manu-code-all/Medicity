import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { json, mockFetch } from "../../test/fetchMock";
import { renderLayout } from "../../test/session";
import { DeskLayout } from "./DeskLayout";

describe("DeskLayout", () => {
  it("gives the receptionist the three desk sections and no administration", async () => {
    mockFetch(() => json(200, { items: [], unread: 0 }));
    renderLayout(<DeskLayout />, "/desk", { userId: "u1", fullName: "Anu Desk", role: "RECEPTIONIST" });

    expect(screen.getByRole("navigation", { name: "Front desk" })).toBeInTheDocument();
    expect(screen.getByText("Receptionist")).toBeInTheDocument();
    for (const name of ["Today", "Walk-in", "Book a visit"]) {
      expect(screen.getByRole("link", { name })).toBeInTheDocument();
    }
    expect(screen.queryByRole("link", { name: "My clinic" })).toBeNull();
    expect(await screen.findByText("Page body")).toBeInTheDocument();
  });

  it("lets the clinic administrator cover the desk and get back to the clinic", () => {
    mockFetch(() => json(200, { items: [], unread: 0 }));
    renderLayout(<DeskLayout />, "/desk", { userId: "u2", fullName: "Asha Admin", role: "CLINIC_ADMIN" });

    expect(screen.getByRole("link", { name: "My clinic" })).toHaveAttribute("href", "/clinic");
  });

  it("explains instead of loading for a patient", () => {
    mockFetch(() => json(200, {}));
    renderLayout(<DeskLayout />, "/desk", { userId: "u3", fullName: "Meera", role: "PATIENT" });

    expect(screen.getByRole("heading", { name: "This workspace is for a clinic's front desk" })).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Front desk" })).toBeNull();
  });
});
