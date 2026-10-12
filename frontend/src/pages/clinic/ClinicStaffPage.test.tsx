import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { ClinicStaffMember } from "../../api/types";
import { json, mockFetch } from "../../test/fetchMock";
import { renderPage } from "../../test/renderPage";
import { ClinicStaffPage } from "./ClinicStaffPage";

const STAFF: ClinicStaffMember[] = [
  { userId: "a1", fullName: "Asha Admin", email: "asha@x.example", phone: null, role: "CLINIC_ADMIN", doctorId: null,
    specialization: null, verified: false, enabled: true, mustChangePassword: false },
  { userId: "r1", fullName: "Anu Desk", email: "anu@x.example", phone: null, role: "RECEPTIONIST", doctorId: null,
    specialization: null, verified: false, enabled: true, mustChangePassword: true },
  { userId: "d1", fullName: "Dr. Alder", email: "alder@x.example", phone: null, role: "DOCTOR", doctorId: "doc1",
    specialization: "ENT", verified: false, enabled: true, mustChangePassword: false },
];

describe("ClinicStaffPage", () => {
  it("lists everyone; the administrator's own account has no reset or disable", async () => {
    mockFetch(() => json(200, STAFF));
    renderPage(<ClinicStaffPage />);

    expect(await screen.findByText("Anu Desk")).toBeInTheDocument();
    expect(screen.getByText("Has not signed in yet")).toBeInTheDocument();
    expect(screen.getByText("Waiting for Medicity to verify")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reset password for Asha Admin" })).toBeNull();
    expect(screen.getByRole("button", { name: "Disable Anu Desk" })).toBeInTheDocument();
  });

  it("adds a receptionist and shows the temporary password once", async () => {
    const calls = mockFetch(({ url, method }) => {
      if (method === "POST") {
        return json(201, { userId: "r2", email: "rita@x.example", fullName: "Rita", role: "RECEPTIONIST", temporaryPassword: "ABCD-EFGH-JKLM" });
      }
      return json(200, url.endsWith("/staff") ? STAFF : []);
    });
    renderPage(<ClinicStaffPage />);

    await userEvent.click(await screen.findByRole("button", { name: "Add a receptionist" }));
    await userEvent.type(screen.getByLabelText("Full name"), "Rita");
    await userEvent.type(screen.getByLabelText("Email"), "rita@x.example");
    await userEvent.click(screen.getByRole("button", { name: "Add receptionist" }));

    expect(await screen.findByLabelText("Temporary password")).toHaveTextContent("ABCD-EFGH-JKLM");
    expect(calls.find((c) => c.method === "POST")!.body).toEqual({ fullName: "Rita", email: "rita@x.example" });

    await userEvent.click(screen.getByRole("button", { name: "I have noted it" }));
    expect(screen.queryByLabelText("Temporary password")).toBeNull();
  });

  it("resets a password and disables an account through the clinic API", async () => {
    const calls = mockFetch(({ url, method }) => {
      if (method === "POST" && url.endsWith("reset-password")) {
        return json(200, { userId: "r1", email: "anu@x.example", fullName: "Anu Desk", role: "RECEPTIONIST", temporaryPassword: "NEWP-ASSW-ORD2" });
      }
      return json(200, method === "POST" ? STAFF[1] : STAFF);
    });
    renderPage(<ClinicStaffPage />);

    await userEvent.click(await screen.findByRole("button", { name: "Reset password for Anu Desk" }));
    expect(await screen.findByLabelText("Temporary password")).toHaveTextContent("NEWP-ASSW-ORD2");
    await userEvent.click(screen.getByRole("button", { name: "Disable Anu Desk" }));

    await screen.findByLabelText("Temporary password");
    expect(calls.filter((c) => c.method === "POST").map((c) => c.url)).toEqual([
      "/api/v1/clinics/me/staff/r1/reset-password",
      "/api/v1/clinics/me/staff/r1/disable",
    ]);
  });
});
