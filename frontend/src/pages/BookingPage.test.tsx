import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { json, mockFetch, type RecordedCall } from "../test/fetchMock";
import { BookingPage } from "./BookingPage";

const SLOTS = [
  { id: "slot-1", startsAt: "2030-01-07T09:00:00Z", endsAt: "2030-01-07T09:30:00Z", status: "OPEN" },
  { id: "slot-2", startsAt: "2030-01-07T10:00:00Z", endsAt: "2030-01-07T10:30:00Z", status: "OPEN" },
];

const APPOINTMENT = { id: "appt-1", slotId: "slot-1", status: "BOOKED" };

function renderPage(answerBooking: (call: RecordedCall, attempt: number) => Response | Promise<Response>) {
  let bookings = 0;
  const calls = mockFetch((call) => {
    if (call.url.includes("/slots")) return json(200, SLOTS);
    if (call.url === "/api/v1/appointments") return answerBooking(call, ++bookings);
    return json(404, {});
  });
  // Mutations are not retried by default in tests; the page sets its own policy.
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/doctors/d1/book"]}>
        <Routes>
          <Route path="/doctors/:doctorId/book" element={<BookingPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  const posts = () => calls.filter((c) => c.method === "POST");
  return { posts, slotRequests: () => calls.filter((c) => c.url.includes("/slots")) };
}

async function pickFirstSlotAndConfirm(user: ReturnType<typeof userEvent.setup>, reason = "Check-up") {
  const [first] = await screen.findAllByRole("button", { pressed: false });
  await user.click(first!);
  await user.type(screen.getByLabelText("What brings you in?"), reason);
  await user.click(screen.getByRole("button", { name: /^Confirm/ }));
}

const keyOf = (call: RecordedCall | undefined) => call?.headers["Idempotency-Key"];

describe("BookingPage", () => {
  it("retries a network failure with the same Idempotency-Key", async () => {
    const user = userEvent.setup();
    const { posts } = renderPage((_call, attempt) => {
      // The first response is lost in transit; the booking may have happened.
      if (attempt === 1) throw new TypeError("Failed to fetch");
      return json(201, APPOINTMENT, { "Idempotent-Replayed": "true" });
    });

    await pickFirstSlotAndConfirm(user);

    expect(await screen.findByText(/Appointment confirmed/, {}, { timeout: 4000 })).toBeInTheDocument();
    expect(posts()).toHaveLength(2);
    expect(keyOf(posts()[0])).toBeTruthy();
    expect(keyOf(posts()[1])).toBe(keyOf(posts()[0]));
  });

  it("does not retry an error the server answered", async () => {
    const user = userEvent.setup();
    const { posts } = renderPage(() => json(422, { code: "SLOT_TOO_SOON", detail: "Too soon" }));

    await pickFirstSlotAndConfirm(user);

    expect(await screen.findByText(/at least 30 minutes' notice/)).toBeInTheDocument();
    expect(posts()).toHaveLength(1);
  });

  it("keeps the key for a resubmission of the same request and changes it for a different one", async () => {
    const user = userEvent.setup();
    const { posts } = renderPage(() => json(500, { code: "INTERNAL_ERROR", detail: "Try again" }));

    await pickFirstSlotAndConfirm(user, "Fever");
    await screen.findByText("Try again");
    await user.click(screen.getByRole("button", { name: /^Confirm/ }));
    await waitFor(() => expect(posts()).toHaveLength(2));
    expect(keyOf(posts()[1])).toBe(keyOf(posts()[0]));

    // A different reason is a different request: reusing the key would be
    // refused by the server as IDEMPOTENCY_KEY_REUSED.
    await user.type(screen.getByLabelText("What brings you in?"), " and cough");
    await user.click(screen.getByRole("button", { name: /^Confirm/ }));
    await waitFor(() => expect(posts()).toHaveLength(3));
    expect(keyOf(posts()[2])).not.toBe(keyOf(posts()[0]));
  });

  it("explains a lost race and reloads the free slots", async () => {
    const user = userEvent.setup();
    const { slotRequests } = renderPage(() => json(409, { code: "SLOT_ALREADY_BOOKED", detail: "Taken" }));

    await pickFirstSlotAndConfirm(user);

    expect(await screen.findByText(/Someone just booked that time/)).toBeInTheDocument();
    await waitFor(() => expect(slotRequests()).toHaveLength(2));
  });
});
