import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { json, mockFetch } from "../../test/fetchMock";
import { renderLayout } from "../../test/session";
import { DoctorLayout } from "./DoctorLayout";

const PROFILE = {
  id: "d1", fullName: "Dr. Anjali Rao", email: "dr.rao@medicity.demo", specialization: "Cardiology",
  medicalCouncil: "Telangana Medical Council", registrationNumber: "TSMC-1234", qualification: "MD",
  yearsExperience: 12, consultationFee: 500, verified: true, verifiedAt: "2030-01-01T00:00:00Z", registeredAt: "2029-12-01T00:00:00Z",
};
const rao = { userId: "u1", fullName: "Anjali Rao", role: "DOCTOR" as const };

describe("DoctorLayout", () => {
  it("shows the doctor's name and the four workspace sections", async () => {
    mockFetch(({ url }) => (url.includes("notifications") ? json(200, { items: [], unread: 0 }) : json(200, PROFILE)));
    renderLayout(<DoctorLayout />, "/doctor", rao);

    expect(screen.getByRole("navigation", { name: "Doctor workspace" })).toBeInTheDocument();
    expect(screen.getByText("Anjali Rao")).toBeInTheDocument();
    for (const name of ["Schedule", "Front desk", "Your hours", "Fees and insurance"]) {
      expect(screen.getByRole("link", { name })).toBeInTheDocument();
    }
    expect(screen.getByRole("link", { name: "Schedule" })).toHaveClass("is-active");
    expect(await screen.findByText("Page body")).toBeInTheDocument();
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("tells an unverified doctor that patients cannot book them yet", async () => {
    mockFetch(({ url }) =>
      url.includes("notifications") ? json(200, { items: [], unread: 0 }) : json(200, { ...PROFILE, verified: false }),
    );
    renderLayout(<DoctorLayout />, "/doctor", rao);

    const banner = await screen.findByRole("status");
    expect(banner).toHaveTextContent("TSMC-1234");
    expect(banner).toHaveTextContent("Telangana Medical Council");
    expect(banner).toHaveTextContent("patients cannot find or book you");
  });

  it("explains instead of loading when a patient opens the doctor workspace", () => {
    const calls = mockFetch(() => json(200, {}));
    renderLayout(<DoctorLayout />, "/doctor", { userId: "u2", fullName: "Meera", role: "PATIENT" });

    expect(screen.getByRole("heading", { name: "The workspace is for doctors" })).toBeInTheDocument();
    expect(screen.getByText(/signed in as patient/)).toBeInTheDocument();
    expect(calls.filter((c) => c.url.includes("/doctors/me"))).toHaveLength(0);
  });

  it("shows the unread count and signs out to the home page", async () => {
    mockFetch(({ url }) => (url.includes("notifications") ? json(200, { items: [], unread: 3 }) : json(200, PROFILE)));
    renderLayout(<DoctorLayout />, "/doctor", rao);

    expect(await screen.findByRole("link", { name: "Notifications, 3 unread" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /Sign out/ }));
    expect(await screen.findByText("Home page")).toBeInTheDocument();
  });
});
