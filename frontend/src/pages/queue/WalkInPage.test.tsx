import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import type { QueueStatus, QueueToken } from "../../api/types";
import { json, mockFetch } from "../../test/fetchMock";
import { WalkInPage } from "./WalkInPage";

const OPEN: QueueStatus = {
  open: true, closedReason: null, waiting: 2, nowServing: 102, minutesPerPatient: 15, estimatedWaitMinutes: 30,
};
const TOKEN: QueueToken = {
  id: "t5", tokenNo: 105, status: "WAITING", doctorId: "d4", doctorName: "Dr. Kavitha Menon",
  specialization: "General Medicine", patientName: "Meera Nair", reason: "Fever", ahead: 2,
  estimatedWaitMinutes: 45, joinedAt: "2030-01-01T04:00:00Z", calledAt: null,
};

function renderWalkIn() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={["/doctors/d4/walk-in"]}>
        <Routes>
          <Route path="/doctors/:doctorId/walk-in" element={<WalkInPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("WalkInPage", () => {
  it("shows the line, takes a token, then follows it", async () => {
    let joined = false;
    const calls = mockFetch(({ url, method }) => {
      if (method === "POST") {
        joined = true;
        return json(201, TOKEN);
      }
      if (url === "/api/v1/queue/mine") return json(200, joined ? [TOKEN] : []);
      return json(200, OPEN);
    });
    renderWalkIn();

    expect(await screen.findByText(/waiting · about 30 min/)).toHaveTextContent("Now seeing #102");
    await userEvent.type(screen.getByLabelText("What brings you in? (optional)"), "Fever");
    await userEvent.click(screen.getByRole("button", { name: "Take a token" }));

    expect(await screen.findByText("#105")).toBeInTheDocument();
    expect(screen.getByText(/2 ahead of you/)).toHaveTextContent("about 45 min");
    const post = calls.find((c) => c.method === "POST");
    expect(post?.url).toBe("/api/v1/doctors/d4/queue");
    expect(post?.body).toEqual({ reason: "Fever" });
  });

  it("a called token says to go in; a closed queue offers no token", async () => {
    mockFetch(({ url }) => {
      if (url === "/api/v1/queue/mine") return json(200, [{ ...TOKEN, status: "CALLED", ahead: 0 }]);
      return json(200, { ...OPEN, open: false, closedReason: "The doctor has closed today's queue." });
    });
    renderWalkIn();

    expect(await screen.findByText("It's your turn. Please go in now.")).toBeInTheDocument();
    expect(screen.getByText("The doctor has closed today's queue.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Take/ })).not.toBeInTheDocument();
  });
});
