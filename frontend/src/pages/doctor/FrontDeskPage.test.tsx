import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { QueueDesk, QueueToken } from "../../api/types";
import { json, mockFetch } from "../../test/fetchMock";
import { renderPage } from "../../test/renderPage";
import { FrontDeskPage } from "./FrontDeskPage";

const base: Omit<QueueToken, "id" | "tokenNo" | "status" | "patientName"> = {
  doctorId: "d4", doctorName: "Dr. Kavitha Menon", specialization: "General Medicine", reason: null,
  ahead: 0, estimatedWaitMinutes: 0, joinedAt: "2030-01-01T04:00:00Z", calledAt: null,
};
const DESK: QueueDesk = {
  status: { open: true, closedReason: null, waiting: 2, nowServing: 102, minutesPerPatient: 15, estimatedWaitMinutes: 30 },
  tokens: [
    { ...base, id: "t1", tokenNo: 101, status: "SEEN", patientName: "Kavya Reddy" },
    { ...base, id: "t2", tokenNo: 102, status: "CALLED", patientName: "Arjun Mehta" },
    { ...base, id: "t3", tokenNo: 103, status: "WAITING", patientName: "Rohit Mehta", reason: "Stomach ache" },
    { ...base, id: "t4", tokenNo: 104, status: "WAITING", patientName: "Padma Reddy" },
  ],
};

describe("FrontDeskPage", () => {
  it("calls the next walk-in, closes the one called, and shows today's bookings beside them", async () => {
    const calls = mockFetch(({ url, method }) => {
      if (method === "POST") return json(200, DESK.tokens[2]);
      if (url.startsWith("/api/v1/doctors/me/visits")) {
        return json(200, [{
          id: "v1", status: "BOOKED", scheduledAt: "2030-01-01T05:30:00Z", endsAt: "2030-01-01T06:00:00Z",
          reason: null, prescriptionId: null, dispensedAt: null,
          patient: { id: "p1", fullName: "Meera Nair", age: 32, gender: "FEMALE", bloodGroup: "O+" },
        }]);
      }
      return json(200, DESK);
    });
    renderPage(<FrontDeskPage />);

    expect(await screen.findByText("Taking walk-ins · 2 waiting", { exact: false })).toBeInTheDocument();
    expect(screen.getByText("Stomach ache")).toBeInTheDocument();
    expect(await screen.findByRole("link", { name: "Meera Nair" })).toHaveAttribute("href", "/doctor/visits/v1");

    await userEvent.click(screen.getByRole("button", { name: "Call #103" }));
    await userEvent.click(screen.getByRole("button", { name: "Seen" }));

    const posts = calls.filter((c) => c.method === "POST").map((c) => c.url);
    expect(posts).toEqual(["/api/v1/doctors/me/queue/next", "/api/v1/doctors/me/queue/tokens/t2/seen"]);
  });
});
