import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { DeskToday } from "../../api/types";
import { json, mockFetch } from "../../test/fetchMock";
import { renderPage } from "../../test/renderPage";
import { DeskTodayPage } from "./DeskTodayPage";

const TODAY: DeskToday = {
  date: "2030-01-01",
  timezone: "Asia/Kolkata",
  doctors: [
    { id: "d1", fullName: "Dr. Anjali Rao", specialization: "General Medicine", verified: true, walkInsOpen: true, waiting: 2 },
  ],
  visits: [
    {
      id: "v1", doctorId: "d1", doctorName: "Dr. Anjali Rao", scheduledAt: "2030-01-01T04:30:00Z", endsAt: "2030-01-01T05:00:00Z",
      status: "BOOKED", patientName: "Meera Nair", patientPhone: "+919876500101", reason: "Cough", visitType: "IN_PERSON",
      arrivedAt: null, bookedByDesk: true,
    },
    {
      id: "v2", doctorId: "d1", doctorName: "Dr. Anjali Rao", scheduledAt: "2030-01-01T05:00:00Z", endsAt: "2030-01-01T05:30:00Z",
      status: "BOOKED", patientName: "Arjun Mehta", patientPhone: null, reason: null, visitType: "IN_PERSON",
      arrivedAt: "2030-01-01T04:55:00Z", bookedByDesk: false,
    },
  ],
  walkIns: [
    {
      id: "t1", tokenNo: 101, status: "WAITING", doctorId: "d1", doctorName: "Dr. Anjali Rao", specialization: "General Medicine",
      patientName: "Kavya Reddy", reason: null, ahead: 0, estimatedWaitMinutes: 10, joinedAt: "2030-01-01T04:00:00Z", calledAt: null,
    },
  ],
};

describe("DeskTodayPage", () => {
  it("shows the clinic's doctors, the day's visits and the walk-in line", async () => {
    mockFetch(() => json(200, TODAY));
    renderPage(<DeskTodayPage />);

    expect(await screen.findByText("Dr. Anjali Rao")).toBeInTheDocument();
    expect(screen.getByText(/taking walk-ins · 2 waiting/)).toBeInTheDocument();
    const visit = screen.getByRole("article", { name: /Meera Nair at/ });
    expect(within(visit).getByText(/\+919876500101/)).toBeInTheDocument();
    expect(within(visit).getByText(/booked by the desk/)).toBeInTheDocument();
    expect(screen.getByText("#101")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Add a walk-in" })).toHaveAttribute("href", "/desk/walk-in");
  });

  it("marks arrival and a missed visit, and cancels, each through the desk API; an arrived patient cannot be marked missed", async () => {
    const calls = mockFetch(({ method }) => json(200, method === "POST" ? TODAY.visits[0] : TODAY));
    renderPage(<DeskTodayPage />);

    await userEvent.click(await screen.findByRole("button", { name: "Mark Meera Nair arrived" }));
    await userEvent.click(screen.getByRole("button", { name: "Mark Meera Nair as missed" }));
    await userEvent.click(screen.getByRole("button", { name: "Cancel Meera Nair's visit" }));

    expect(calls.filter((c) => c.method === "POST").map((c) => c.url)).toEqual([
      "/api/v1/clinics/me/desk/visits/v1/arrived",
      "/api/v1/clinics/me/desk/visits/v1/no-show",
      "/api/v1/clinics/me/desk/visits/v1/cancel",
    ]);
    // Arjun is already here: only cancelling is offered.
    expect(screen.queryByRole("button", { name: "Mark Arjun Mehta as missed" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Mark Arjun Mehta arrived" })).toBeNull();
    expect(screen.getByRole("button", { name: "Cancel Arjun Mehta's visit" })).toBeInTheDocument();
  });

  it("shows the server's reason when an action is refused", async () => {
    mockFetch(({ method }) =>
      method === "POST"
        ? json(422, { status: 422, code: "NOT_TODAY", detail: "Arrival can only be marked on the day of the visit" })
        : json(200, TODAY),
    );
    renderPage(<DeskTodayPage />);

    await userEvent.click(await screen.findByRole("button", { name: "Mark Meera Nair arrived" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Arrival can only be marked on the day of the visit");
  });
});
