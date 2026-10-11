import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { QueueToken } from "../../api/types";
import { json, mockFetch } from "../../test/fetchMock";
import { renderPage } from "../../test/renderPage";
import { TokensPage } from "./TokensPage";

const TOKEN: QueueToken = {
  id: "t1", tokenNo: 104, status: "WAITING", doctorId: "d1", doctorName: "Dr. Menon", specialization: "General",
  patientName: "Meera Nair", reason: null, ahead: 2, estimatedWaitMinutes: 20, joinedAt: "2030-01-01T09:00:00Z", calledAt: null,
};

describe("TokensPage", () => {
  it("shows the token number and the place in line", async () => {
    mockFetch(() => json(200, [TOKEN]));
    renderPage(<TokensPage />);
    expect(await screen.findByText("#104")).toBeInTheDocument();
    expect(screen.getByText(/2 ahead of you · about 20 min/)).toBeInTheDocument();
  });

  it("tells a called patient to go in", async () => {
    mockFetch(() => json(200, [{ ...TOKEN, status: "CALLED", ahead: 0 }]));
    renderPage(<TokensPage />);
    expect(await screen.findByText("It's your turn. Please go in now.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Leave the queue" })).toBeNull();
  });

  it("leaves the queue after confirming", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const calls = mockFetch(({ method }) => json(200, method === "POST" ? { ...TOKEN, status: "LEFT" } : [TOKEN]));
    renderPage(<TokensPage />);

    await userEvent.click(await screen.findByRole("button", { name: "Leave the queue" }));

    await vi.waitFor(() => expect(calls.some((c) => c.method === "POST" && c.url === "/api/v1/queue/tokens/t1/leave")).toBe(true));
  });

  it("does not leave when the confirmation is declined", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(false);
    const calls = mockFetch(() => json(200, [TOKEN]));
    renderPage(<TokensPage />);
    await userEvent.click(await screen.findByRole("button", { name: "Leave the queue" }));
    expect(calls.some((c) => c.method === "POST")).toBe(false);
  });

  it("points at the directory when there is no token today", async () => {
    mockFetch(() => json(200, []));
    renderPage(<TokensPage />);
    expect(await screen.findByRole("heading", { name: "No tokens today" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "directory" })).toHaveAttribute("href", "/doctors");
  });
});
