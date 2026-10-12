import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { json, mockFetch } from "../test/fetchMock";
import { renderPage } from "../test/renderPage";
import { ClinicRecordsPanel } from "./ClinicRecordsPanel";

const LINKED = {
  id: "r1", clinicName: "Dr. Anjali Rao", fullName: "Meera Nair", phone: "9876543210", email: "meera@example.com",
  dateOfBirth: "1994-08-12", gender: "FEMALE", bloodGroup: "O+", allergies: "Penicillin",
  notes: "Prefers morning visits", acceptedAt: "2030-01-01T00:00:00Z",
};

describe("ClinicRecordsPanel", () => {
  it("asks an unverified address to be confirmed first, and shows nothing about any clinic", async () => {
    const calls = mockFetch(({ url }) =>
      url.endsWith("/clinic-records")
        ? json(200, { emailVerified: false, offers: [], linked: [] })
        : url.endsWith("/email-code")
          ? json(200, { delivery: "EMAIL", sentTo: "m•••@example.com", demoCode: null, expiresInSeconds: 300 })
          : json(204, null),
    );
    renderPage(<ClinicRecordsPanel />);

    expect(await screen.findByText(/first confirm that this email address is yours/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Email me a code" }));
    await userEvent.type(await screen.findByLabelText(/Code sent to m•••@example.com/), "123456");
    await userEvent.click(screen.getByRole("button", { name: "Confirm" }));

    expect(calls.find((c) => c.url.endsWith("/email-code/verify"))?.body).toEqual({ code: "123456" });
  });

  it("explains when emailing codes is not available", async () => {
    mockFetch(({ url }) =>
      url.endsWith("/clinic-records")
        ? json(200, { emailVerified: false, offers: [], linked: [] })
        : json(200, { delivery: "UNAVAILABLE", sentTo: null, demoCode: null, expiresInSeconds: 0 }),
    );
    renderPage(<ClinicRecordsPanel />);

    await userEvent.click(await screen.findByRole("button", { name: "Email me a code" }));

    expect(await screen.findByText(/not available right now/)).toBeInTheDocument();
    expect(screen.queryByLabelText(/Code/)).toBeNull();
  });

  it("shows a verified patient only the clinic's name until they say yes, then links on yes", async () => {
    let answered = false;
    const calls = mockFetch(({ method }) => {
      if (method === "POST") {
        answered = true;
        return json(204, null);
      }
      return json(200, answered
        ? { emailVerified: true, offers: [], linked: [LINKED] }
        : { emailVerified: true, offers: [{ id: "r1", clinicName: "Dr. Anjali Rao" }], linked: [] });
    });
    renderPage(<ClinicRecordsPanel />);

    expect(await screen.findByText(/already has records for you/)).toHaveTextContent("Dr. Anjali Rao");
    expect(screen.queryByText("Prefers morning visits")).toBeNull();
    expect(screen.queryByText("9876543210")).toBeNull();

    await userEvent.click(screen.getByRole("button", { name: "Yes, link them" }));

    expect(await screen.findByText("Prefers morning visits")).toBeInTheDocument();
    expect(screen.getByText("Penicillin")).toBeInTheDocument();
    expect(calls.find((c) => c.method === "POST")?.url).toBe("/api/v1/patients/me/clinic-records/r1/accept");
  });

  it("says no without ever showing what the clinic holds", async () => {
    let answered = false;
    const calls = mockFetch(({ method }) => {
      if (method === "POST") {
        answered = true;
        return json(204, null);
      }
      return json(200, {
        emailVerified: true,
        offers: answered ? [] : [{ id: "r1", clinicName: "Dr. Anjali Rao" }],
        linked: [],
      });
    });
    renderPage(<ClinicRecordsPanel />);

    await userEvent.click(await screen.findByRole("button", { name: "No thanks" }));

    expect(await screen.findByText("Nothing is waiting for you.")).toBeInTheDocument();
    expect(calls.find((c) => c.method === "POST")?.url).toBe("/api/v1/patients/me/clinic-records/r1/decline");
  });

  it("renders nothing when it cannot be loaded", async () => {
    mockFetch(() => json(500, { code: "ERR" }));
    const { container } = renderPage(<ClinicRecordsPanel />);
    await new Promise((r) => setTimeout(r, 20));
    expect(container).toBeEmptyDOMElement();
  });
});
