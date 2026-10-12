import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { PatientProfile } from "../../api/types";
import { json, mockFetch } from "../../test/fetchMock";
import { renderPage } from "../../test/renderPage";
import { ProfilePage } from "./ProfilePage";

const PROFILE: PatientProfile = {
  patientId: "p1", relationship: null, fullName: "Meera Nair", email: "patient@medicity.demo", phone: null,
  dateOfBirth: "1990-05-01", gender: "FEMALE", bloodGroup: "O+", addressLine: "12 MG Road", city: "Bengaluru",
  emergencyContact: null, heightCm: 165, weightKg: null, allergies: "Penicillin", chronicConditions: null,
  currentMedications: null, homeLatitude: 12.97, homeLongitude: 77.59, memberSince: "2029-01-01T00:00:00Z",
};

describe("ProfilePage", () => {
  it("shows what is known and marks the rest as not provided", async () => {
    mockFetch(() => json(200, PROFILE));
    renderPage(<ProfilePage />);

    expect(await screen.findByRole("heading", { name: "Meera Nair" })).toBeInTheDocument();
    expect(screen.getByText("O+")).toBeInTheDocument();
    expect(screen.getByText("165 cm")).toBeInTheDocument();
    expect(screen.getByText("Penicillin")).toBeInTheDocument();
    expect(screen.getByText("12 MG Road, Bengaluru")).toBeInTheDocument();
    expect(screen.getByText("Your saved location")).toBeInTheDocument();
    // phone, conditions, medicines, emergency contact
    expect(screen.getAllByText("Not provided").length).toBeGreaterThanOrEqual(4);
  });

  it("opens the form, saves the whole record and returns to the details", async () => {
    const calls = mockFetch(({ method }) =>
      method === "PUT" ? json(200, { ...PROFILE, allergies: "Penicillin, dust" }) : json(200, PROFILE),
    );
    renderPage(<ProfilePage />);

    await userEvent.click(await screen.findByRole("button", { name: "Edit health and location" }));
    const allergies = screen.getByLabelText(/Allergies/);
    await userEvent.clear(allergies);
    await userEvent.type(allergies, "Penicillin, dust");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByText("Penicillin, dust")).toBeInTheDocument();
    const put = calls.find((c) => c.method === "PUT")!;
    expect(put.url).toBe("/api/v1/patients/me");
    expect(put.body).toMatchObject({ allergies: "Penicillin, dust", bloodGroup: "O+", heightCm: 165 });
  });

  it("says so when the profile cannot load", async () => {
    mockFetch(() => json(500, { detail: "boom" }));
    renderPage(<ProfilePage />);
    expect(await screen.findByText("Could not load your profile.")).toBeInTheDocument();
  });
});
