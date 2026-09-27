import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { DoctorProfile } from "../../api/types";
import { AuthContext, type AuthContextValue } from "../../auth/context";
import { json, mockFetch } from "../../test/fetchMock";
import { renderPage } from "../../test/renderPage";
import { PendingDoctorsPage } from "./PendingDoctorsPage";

const NISHA: DoctorProfile = {
  id: "d9", fullName: "Dr. Nisha Rao", email: "nisha@example.com", specialization: "Dermatology",
  medicalCouncil: "Karnataka Medical Council", registrationNumber: "KMC-12345", qualification: "MBBS, MD",
  yearsExperience: 6, consultationFee: 800, verified: false, verifiedAt: null, registeredAt: "2030-01-01T09:00:00Z",
};

describe("PendingDoctorsPage", () => {
  it("shows the number and council to check, and verifying takes the doctor off the list", async () => {
    let pending = [NISHA];
    const calls = mockFetch(({ method }) => {
      if (method === "POST") {
        pending = [];
        return json(200, { ...NISHA, verified: true });
      }
      return json(200, pending);
    });
    const value = { session: { userId: "a1", fullName: "Ops", role: "ADMIN" } } as AuthContextValue;
    renderPage(
      <AuthContext.Provider value={value}>
        <PendingDoctorsPage />
      </AuthContext.Provider>,
    );

    expect(await screen.findByText("KMC-12345")).toBeInTheDocument();
    expect(screen.getByText("Karnataka Medical Council")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Stores" })).toHaveAttribute("href", "/admin/stores");

    await userEvent.click(screen.getByRole("button", { name: /verify doctor/ }));

    expect(await screen.findByText("Nothing waiting")).toBeInTheDocument();
    expect(calls.find((c) => c.method === "POST")?.url).toBe("/api/v1/admin/doctors/d9/verify");
  });
});
