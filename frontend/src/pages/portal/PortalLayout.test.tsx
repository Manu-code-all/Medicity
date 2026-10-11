import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { json, mockFetch } from "../../test/fetchMock";
import { renderLayout } from "../../test/session";
import { PortalLayout } from "./PortalLayout";

const FAMILY = [{ patientId: "p1", fullName: "Meera Nair", relationship: null, self: true, age: 32 }];

describe("PortalLayout", () => {
  it("gives a patient the nine portal sections and a Book a visit button", async () => {
    mockFetch(({ url }) => (url.includes("family") ? json(200, FAMILY) : json(200, { items: [], unread: 0 })));
    renderLayout(<PortalLayout />, "/portal", { userId: "u1", fullName: "Meera Nair", role: "PATIENT" });

    const nav = screen.getByRole("navigation", { name: "Patient portal" });
    expect(nav.querySelectorAll("a")).toHaveLength(9);
    expect(screen.getByRole("link", { name: "Overview" })).toHaveClass("is-active");
    expect(screen.getByRole("link", { name: "Book a visit" })).toHaveAttribute("href", "/doctors");
    expect(screen.getByRole("link", { name: "Chemist answers" })).toHaveAttribute("href", "/portal/requests");
    expect(await screen.findByText("Page body")).toBeInTheDocument();
  });

  it("sends a doctor to their own workspace", () => {
    mockFetch(() => json(200, {}));
    renderLayout(<PortalLayout />, "/portal", { userId: "u2", fullName: "Dr Rao", role: "DOCTOR" });
    expect(screen.getByRole("heading", { name: "The portal is for patients" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Go to your workspace" })).toHaveAttribute("href", "/doctor");
  });

  it("sends a chemist to the doctor directory", () => {
    mockFetch(() => json(200, {}));
    renderLayout(<PortalLayout />, "/portal", { userId: "u3", fullName: "Ravi", role: "CHEMIST" });
    expect(screen.getByRole("link", { name: "Go to the doctor directory" })).toHaveAttribute("href", "/doctors");
  });
});
