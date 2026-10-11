import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { QueueEntry } from "../../api/types";
import { json, mockFetch } from "../../test/fetchMock";
import { renderPage } from "../../test/renderPage";
import { QueuePage } from "./QueuePage";

const PENDING: QueueEntry = {
  id: "q1", createdAt: "2030-01-01T10:00:00Z", expiresAt: "2099-01-01T16:00:00Z", requestStatus: "OPEN",
  myStatus: "PENDING", answeredAt: null, distanceM: 850, patientName: "Meera", doctorName: "Dr. Anjali Rao", medicines: 2,
};
const ANSWERED: QueueEntry = { ...PENDING, id: "q2", myStatus: "ANSWERED", patientName: "Arun", medicines: 1 };

function server() {
  return mockFetch(({ url }) => {
    if (url.includes("requests?show=pending")) return json(200, [PENDING]);
    if (url.includes("requests?show=answered")) return json(200, [ANSWERED]);
    if (url.includes("reservations")) return json(200, [{}, {}]);
    if (url.includes("insights")) {
      return json(200, { from: "", to: "", medicines: [], summary: { questionsReceived: 9, answered: 8, answeredAutomatically: 1, reservations: 3, collected: 2, medianMinutesToAnswer: 6 } });
    }
    return json(404, {});
  });
}

describe("store QueuePage", () => {
  it("lists the waiting questions and the figures above them", async () => {
    server();
    renderPage(<QueuePage />);

    const row = (await screen.findByText(/Meera · 2 medicines/)).closest("a")!;
    expect(row).toHaveAttribute("href", "/store/requests/q1");
    expect(row).toHaveTextContent("Prescribed by Dr. Anjali Rao");
    expect(row).toHaveTextContent("closes");

    expect(await screen.findByText("Median this week, 8 of 9 answered")).toBeInTheDocument();
    expect(screen.getByText("To keep aside").closest("a")).toHaveTextContent("2");
    expect(screen.getByText("Waiting for you", { selector: ".tile__label" }).closest("div")).toHaveTextContent("1");
  });

  it("switches to the answered tab", async () => {
    const calls = server();
    renderPage(<QueuePage />);
    await screen.findByText(/Meera · 2 medicines/);

    await userEvent.click(screen.getByRole("tab", { name: "Answered" }));

    const row = (await screen.findByText(/Arun · 1 medicine$/)).closest("a")!;
    expect(row).toHaveTextContent("Answered");
    expect(calls.some((c) => c.url.endsWith("show=answered"))).toBe(true);
  });

  it("says nothing is waiting when the queue is empty", async () => {
    mockFetch(({ url }) => (url.includes("insights") ? json(404, {}) : json(200, [])));
    renderPage(<QueuePage />);
    expect(await screen.findByRole("heading", { name: "Nothing waiting" })).toBeInTheDocument();
  });

  it("shows an error when the questions cannot load", async () => {
    mockFetch(() => json(500, { detail: "boom" }));
    renderPage(<QueuePage />);
    expect(await screen.findByText("Could not load questions.")).toBeInTheDocument();
  });
});
