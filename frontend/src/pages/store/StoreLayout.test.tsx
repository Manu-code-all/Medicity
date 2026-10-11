import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { json, mockFetch } from "../../test/fetchMock";
import { renderLayout } from "../../test/session";
import { StoreLayout } from "./StoreLayout";

describe("StoreLayout", () => {
  it("shows the chemist's name and the store's five sections", async () => {
    mockFetch(() => json(200, { items: [], unread: 0 }));
    renderLayout(<StoreLayout />, "/store", { userId: "u1", fullName: "Ravi Kumar", role: "CHEMIST" });

    expect(screen.getByRole("navigation", { name: "Store workspace" })).toBeInTheDocument();
    expect(screen.getByText("Ravi Kumar")).toBeInTheDocument();
    for (const name of ["Questions", "Pick-ups", "Insights", "Live stock", "My store"]) {
      expect(screen.getByRole("link", { name })).toBeInTheDocument();
    }
    expect(screen.getByRole("link", { name: "My store" })).toHaveClass("is-active");
    expect(screen.getByRole("link", { name: "Questions" })).not.toHaveClass("is-active");
    expect(await screen.findByText("Page body")).toBeInTheDocument();
  });

  it("explains instead of loading when someone else opens the store workspace", () => {
    mockFetch(() => json(200, {}));
    renderLayout(<StoreLayout />, "/store", { userId: "u2", fullName: "Dr Rao", role: "DOCTOR" });

    expect(screen.getByRole("heading", { name: "This workspace is for chemists" })).toBeInTheDocument();
    expect(screen.getByText(/signed in as doctor/)).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Store workspace" })).toBeNull();
  });
});
