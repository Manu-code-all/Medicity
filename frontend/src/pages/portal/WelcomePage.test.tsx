import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { PatientProfile } from "../../api/types";
import { json, mockFetch } from "../../test/fetchMock";
import { renderPage } from "../../test/renderPage";
import { WelcomePage } from "./WelcomePage";

const PROFILE: PatientProfile = {
  patientId: "p1", relationship: null, fullName: "Manu Gupta", email: "m@x.test", phone: null, dateOfBirth: "2006-10-28",
  gender: "MALE", bloodGroup: null, addressLine: null, city: null, emergencyContact: null, heightCm: null, weightKg: null,
  allergies: null, chronicConditions: null, currentMedications: null, homeLatitude: null, homeLongitude: null,
  memberSince: "2026-09-30T10:00:00Z",
};

describe("WelcomePage", () => {
  it("asks what a clinic asks, and sends the whole form with the saved location", async () => {
    const position = { coords: { latitude: 12.9719, longitude: 77.6412 } } as GeolocationPosition;
    Object.defineProperty(globalThis.navigator, "geolocation", {
      configurable: true,
      value: { getCurrentPosition: (ok: PositionCallback) => ok(position) },
    });
    const calls = mockFetch(({ method }) => json(200, method === "PUT" ? { ...PROFILE, bloodGroup: "B+" } : PROFILE));
    renderPage(<WelcomePage />);

    expect(await screen.findByRole("heading", { name: "Welcome, Manu" })).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText("Blood group"), "B+");
    await userEvent.type(screen.getByLabelText("Height (cm)"), "172");
    await userEvent.type(screen.getByLabelText("Allergies"), "Penicillin");
    await userEvent.click(screen.getByRole("button", { name: /Use my current location/ }));
    expect(await screen.findByText("Location set")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Save and continue" }));

    const put = calls.find((c) => c.method === "PUT");
    expect(put?.url).toBe("/api/v1/patients/me");
    expect(put?.body).toMatchObject({
      bloodGroup: "B+", heightCm: 172, weightKg: null, allergies: "Penicillin", chronicConditions: null,
      homeLatitude: 12.9719, homeLongitude: 77.6412,
    });
  });

  it("can be skipped, sending nothing", async () => {
    const calls = mockFetch(() => json(200, PROFILE));
    renderPage(<WelcomePage />);

    await userEvent.click(await screen.findByRole("button", { name: "Skip for now" }));
    expect(calls.some((c) => c.method === "PUT")).toBe(false);
  });
});
