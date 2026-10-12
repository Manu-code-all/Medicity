import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { EmailPreference } from "../../api/types";
import { json, mockFetch } from "../../test/fetchMock";
import { renderPage } from "../../test/renderPage";
import { ProfilePage } from "./ProfilePage";

const PROFILE = {
  patientId: "p1",
  relationship: null,
  fullName: "Meera Nair",
  email: "meera@example.test",
  phone: null,
  dateOfBirth: "1994-08-12",
  gender: "FEMALE",
  bloodGroup: null,
  heightCm: null,
  weightKg: null,
  allergies: null,
  chronicConditions: null,
  currentMedications: null,
  addressLine: null,
  city: null,
  homeLatitude: null,
  homeLongitude: null,
  emergencyContact: null,
  memberSince: "2029-01-01T00:00:00Z",
};

function server(preference: EmailPreference) {
  let current = preference;
  return mockFetch(({ url, method, body }) => {
    if (url === "/api/v1/notifications/email-preference") {
      if (method === "PUT") current = { ...current, enabled: (body as { enabled: boolean }).enabled };
      return json(200, current);
    }
    return json(200, PROFILE);
  });
}

describe("ProfilePage email setting", () => {
  it("shows the switch on, and turning it off saves the choice", async () => {
    const calls = server({ enabled: true, available: true });
    renderPage(<ProfilePage />);

    const box = await screen.findByRole("checkbox", { name: "Email me about my visits and prescriptions" });
    expect(box).toBeChecked();
    await userEvent.click(box);

    await waitFor(() => expect(box).not.toBeChecked());
    expect(calls.find((c) => c.method === "PUT")?.body).toEqual({ enabled: false });
  });

  it("says what the emails leave out", async () => {
    server({ enabled: true, available: true });
    renderPage(<ProfilePage />);

    expect(await screen.findByText(/never include medical details/)).toBeInTheDocument();
  });

  it("offers no switch when the server cannot send email", async () => {
    server({ enabled: true, available: false });
    renderPage(<ProfilePage />);

    await screen.findByText("Meera Nair");
    await waitFor(() => expect(screen.queryByRole("checkbox")).not.toBeInTheDocument());
  });

  it("keeps the switch where it was and says so when saving fails", async () => {
    mockFetch(({ url, method }) =>
      url === "/api/v1/notifications/email-preference"
        ? method === "PUT" ? json(500, { code: "ERR" }) : json(200, { enabled: true, available: true })
        : json(200, PROFILE),
    );
    renderPage(<ProfilePage />);

    const box = await screen.findByRole("checkbox");
    await userEvent.click(box);

    expect(await screen.findByText("Could not save this. Try again.")).toBeInTheDocument();
    expect(box).toBeChecked();
  });
});
