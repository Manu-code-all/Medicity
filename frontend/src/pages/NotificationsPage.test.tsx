import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { json, mockFetch } from "../test/fetchMock";
import { NotificationsPage } from "./NotificationsPage";

const LIST = {
  unread: 1,
  items: [
    {
      id: "n1",
      kind: "APPOINTMENT_BOOKED",
      title: "Appointment confirmed",
      body: "With Dr. Anjali Rao.",
      link: "/portal/visits",
      occursAt: "2030-01-07T09:00:00Z",
      createdAt: "2030-01-01T10:00:00Z",
      read: false,
    },
    {
      id: "n2",
      kind: "PRESCRIPTION_ISSUED",
      title: "New prescription",
      body: "Dr. Anjali Rao prescribed for Viral fever.",
      link: null,
      occursAt: null,
      createdAt: "2029-12-20T10:00:00Z",
      read: true,
    },
  ],
};

function renderPage() {
  const calls = mockFetch(({ url, method }) =>
    method === "POST" ? json(204, null) : url === "/api/v1/notifications" ? json(200, LIST) : json(404, {}),
  );
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/notifications"]}>
        <Routes>
          <Route path="/notifications" element={<NotificationsPage />} />
          <Route path="/portal/visits" element={<p>Visits page</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return calls;
}

describe("NotificationsPage", () => {
  it("lists notifications, newest first, with the visit time when there is one", async () => {
    renderPage();

    const [first, second] = await screen.findAllByRole("button", { name: /Appointment confirmed|New prescription/ });
    expect(first).toHaveTextContent("Appointment confirmed");
    expect(first).toHaveTextContent("With Dr. Anjali Rao.");
    expect(first).toHaveClass("notification--unread");
    expect(second).not.toHaveClass("notification--unread");
    expect(screen.getByRole("button", { name: "Mark all as read" })).toBeInTheDocument();
  });

  it("marks an unread notification read and follows its link", async () => {
    const user = userEvent.setup();
    const calls = renderPage();

    await user.click(await screen.findByRole("button", { name: /Appointment confirmed/ }));

    expect(await screen.findByText("Visits page")).toBeInTheDocument();
    await waitFor(() =>
      expect(calls.some((c) => c.method === "POST" && c.url === "/api/v1/notifications/n1/read")).toBe(true),
    );
  });

  it("does not mark an already-read notification again", async () => {
    const user = userEvent.setup();
    const calls = renderPage();

    await user.click(await screen.findByRole("button", { name: /New prescription/ }));

    expect(calls.filter((c) => c.method === "POST")).toHaveLength(0);
  });
});
