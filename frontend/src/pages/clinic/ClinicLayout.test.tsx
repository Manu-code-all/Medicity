import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { json, mockFetch } from "../../test/fetchMock";
import { renderLayout } from "../../test/session";
import { ClinicLayout } from "./ClinicLayout";

describe("ClinicLayout", () => {
  it("gives the clinic administrator the clinic's sections", async () => {
    mockFetch(() => json(200, { items: [], unread: 0 }));
    renderLayout(<ClinicLayout />, "/clinic", { userId: "u1", fullName: "Asha Admin", role: "CLINIC_ADMIN" });

    expect(screen.getByRole("navigation", { name: "Clinic workspace" })).toBeInTheDocument();
    expect(screen.getByText("Asha Admin")).toBeInTheDocument();
    expect(screen.getByText("Clinic administrator")).toBeInTheDocument();
    for (const name of ["My clinic", "People", "Report", "Front desk"]) {
      expect(screen.getByRole("link", { name })).toBeInTheDocument();
    }
    expect(screen.getByRole("link", { name: "My clinic" })).toHaveClass("is-active");
    expect(await screen.findByText("Page body")).toBeInTheDocument();
  });

  it("explains instead of loading when a receptionist opens the administrator's workspace", () => {
    mockFetch(() => json(200, {}));
    renderLayout(<ClinicLayout />, "/clinic", { userId: "u2", fullName: "Anu Desk", role: "RECEPTIONIST" });

    expect(screen.getByRole("heading", { name: "This workspace is for clinic administrators" })).toBeInTheDocument();
    expect(screen.getByText(/signed in as receptionist/)).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Clinic workspace" })).toBeNull();
  });
});
