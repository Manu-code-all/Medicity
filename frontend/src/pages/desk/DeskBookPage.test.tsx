import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { json, mockFetch } from "../../test/fetchMock";
import { renderPage } from "../../test/renderPage";
import { DeskBookPage } from "./DeskBookPage";
import { DeskWalkInPage } from "./DeskWalkInPage";

const TODAY = {
  date: "2030-01-01",
  timezone: "Asia/Kolkata",
  doctors: [
    { id: "d1", fullName: "Dr. Anjali Rao", specialization: "General Medicine", verified: true, walkInsOpen: true, waiting: 0 },
    { id: "d2", fullName: "Dr. Closed", specialization: "ENT", verified: true, walkInsOpen: false, waiting: 0 },
  ],
  visits: [],
  walkIns: [],
};

describe("Booking and walk-ins at the desk", () => {
  it("books a new caller into an open time of a clinic doctor", async () => {
    const calls = mockFetch(({ url, method }) => {
      if (url.includes("/doctors/d1/slots")) {
        return json(200, [{ id: "s1", startsAt: "2030-01-02T05:00:00Z", endsAt: "2030-01-02T05:30:00Z" }]);
      }
      if (method === "POST") {
        return json(201, {
          id: "v9", doctorId: "d1", doctorName: "Dr. Anjali Rao", scheduledAt: "2030-01-02T05:00:00Z", endsAt: "2030-01-02T05:30:00Z",
          status: "BOOKED", patientName: "Ravi Shankar", patientPhone: "9876500000", reason: null, visitType: null,
          arrivedAt: null, bookedByDesk: true,
        });
      }
      return json(200, TODAY);
    });
    renderPage(<DeskBookPage />);

    await screen.findByRole("option", { name: /Dr. Anjali Rao/ });
    await userEvent.selectOptions(screen.getByLabelText("Doctor"), "d1");
    const time = await screen.findByLabelText("Time");
    await userEvent.selectOptions(time, "s1");
    await userEvent.click(screen.getByRole("radio", { name: "A new caller" }));
    await userEvent.type(screen.getByLabelText("Full name"), "Ravi Shankar");
    await userEvent.type(screen.getByLabelText("Phone"), "9876500000");
    await userEvent.type(screen.getByLabelText("Age (years)"), "52");
    await userEvent.click(screen.getByRole("button", { name: "Book the visit" }));

    expect(await screen.findByText(/Booked Ravi Shankar with Dr. Anjali Rao/)).toBeInTheDocument();
    const post = calls.find((c) => c.method === "POST")!;
    expect(post.url).toBe("/api/v1/clinics/me/desk/visits");
    expect(post.body).toEqual({
      slotId: "s1",
      patient: { newPatient: { fullName: "Ravi Shankar", phone: "9876500000", ageYears: 52 } },
      reason: null,
    });
  });

  it("will not book until the time and the person are both chosen", async () => {
    mockFetch(() => json(200, TODAY));
    renderPage(<DeskBookPage />);

    expect(await screen.findByRole("button", { name: "Book the visit" })).toBeDisabled();
  });

  it("finds an existing account by email for a walk-in, and offers only doctors taking walk-ins", async () => {
    const calls = mockFetch(({ url, method }) => {
      if (url.includes("/desk/patients")) {
        return json(200, [{ patientId: null, email: "meera@x.example", fullName: "Meera Nair", phone: null, source: "ACCOUNT" }]);
      }
      if (method === "POST") {
        return json(201, {
          id: "t5", tokenNo: 104, status: "WAITING", doctorId: "d1", doctorName: "Dr. Anjali Rao", specialization: "General Medicine",
          patientName: "Meera Nair", reason: null, ahead: 3, estimatedWaitMinutes: 45, joinedAt: "2030-01-01T04:00:00Z", calledAt: null,
        });
      }
      return json(200, TODAY);
    });
    renderPage(<DeskWalkInPage />);

    expect(await screen.findByRole("option", { name: /Dr. Closed.*walk-ins closed/ })).toBeDisabled();
    await userEvent.selectOptions(screen.getByLabelText("Doctor"), "d1");
    await userEvent.type(screen.getByLabelText("Name, phone or full email"), "meera@x.example");
    await userEvent.click(screen.getByRole("button", { name: "Search" }));
    await userEvent.click(await screen.findByRole("button", { name: "Meera Nair" }));
    await userEvent.click(screen.getByRole("button", { name: "Give a token" }));

    expect(await screen.findByText(/Token #104 for Meera Nair/)).toBeInTheDocument();
    expect(calls.find((c) => c.method === "POST")!.body).toEqual({
      doctorId: "d1",
      patient: { email: "meera@x.example" },
      reason: null,
    });
  });
});
