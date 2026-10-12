import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { homeFor } from "../../auth/context";
import { json, mockFetch } from "../../test/fetchMock";
import { renderLayout } from "../../test/session";
import { PharmacyLayout } from "./PharmacyLayout";

describe("PharmacyLayout", () => {
  it("shows the pharmacist's name and the two sections", async () => {
    mockFetch(() => json(200, { items: [], unread: 0 }));
    renderLayout(<PharmacyLayout />, "/pharmacy", { userId: "u1", fullName: "Asha Menon", role: "PHARMACIST" });

    expect(screen.getByRole("navigation", { name: "Pharmacy workspace" })).toBeInTheDocument();
    expect(screen.getByText("Asha Menon")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Counter" })).toHaveClass("is-active");
    expect(screen.getByRole("link", { name: "Stock" })).toBeInTheDocument();
    expect(await screen.findByText("Page body")).toBeInTheDocument();
  });

  it("explains instead of loading when an administrator opens it", () => {
    mockFetch(() => json(200, {}));
    renderLayout(<PharmacyLayout />, "/pharmacy", { userId: "u2", fullName: "Ops Admin", role: "ADMIN" });

    expect(screen.getByRole("heading", { name: "The pharmacy is for pharmacists" })).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Pharmacy workspace" })).toBeNull();
  });

  it("lands a pharmacist here after sign-in", () => {
    expect(homeFor("PHARMACIST")).toBe("/pharmacy");
  });
});
