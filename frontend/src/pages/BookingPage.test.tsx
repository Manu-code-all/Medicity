import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
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

function renderPage(answerBooking: (call: RecordedCall, attempt: number) => Response | Promise<Response>, path = "/doctors/d1/book") {
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
      <MemoryRouter initialEntries={[path]}>
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
  it("offers the body guide's answers to the doctor, shared by default and only if the patient keeps it ticked", async () => {
    const intake = { area: "Chest", symptoms: ["Heart racing or skipping beats"], since: "A few days", suggested: "Cardiology" };
    sessionStorage.setItem("medicity.intake", JSON.stringify(intake));
    const user = userEvent.setup();
    const { posts } = renderPage(() => json(201, APPOINTMENT));

    const [first] = await screen.findAllByRole("button", { pressed: false });
    await user.click(first!);
    expect(screen.getByText("Chest · Heart racing or skipping beats · A few days")).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: /Share these answers with the doctor/ })).toBeChecked();
    await user.click(screen.getByRole("button", { name: /^Confirm/ }));

    await waitFor(() => expect(posts()).toHaveLength(1));
    expect(posts()[0]!.body).toMatchObject({ intake });
    await waitFor(() => expect(sessionStorage.getItem("medicity.intake")).toBeNull());
  });

  it("unticked, the answers stay with the patient", async () => {
    sessionStorage.setItem("medicity.intake", JSON.stringify({ area: "Chest", symptoms: [], since: null, suggested: null }));
    const user = userEvent.setup();
    const { posts } = renderPage(() => json(201, APPOINTMENT));

    const [first] = await screen.findAllByRole("button", { pressed: false });
    await user.click(first!);
    await user.click(screen.getByRole("checkbox", { name: /Share these answers/ }));
    await user.click(screen.getByRole("button", { name: /^Confirm/ }));

    await waitFor(() => expect(posts()).toHaveLength(1));
    expect(posts()[0]!.body).toMatchObject({ intake: null });
  });

  it("a full day: join its waiting list, see it listed, and stop waiting", async () => {
    const soon = new Date();
    soon.setDate(soon.getDate() + 3);
    const iso = `${soon.getFullYear()}-${String(soon.getMonth() + 1).padStart(2, "0")}-${String(soon.getDate()).padStart(2, "0")}`;
    const entry = {
      id: "w1", doctorId: "d1", doctorName: "Dr. Anjali Rao", specialization: "Cardiology", patientId: "p1",
      patientName: "Meera Nair", date: iso, status: "ACTIVE",
    };
    let joined = false;
    const calls = mockFetch((call) => {
      if (call.url.includes("/slots")) return json(200, SLOTS);
      if (call.url === "/api/v1/doctors/d1/waitlist" && call.method === "POST") {
        joined = true;
        return json(201, entry);
      }
      if (call.method === "DELETE") {
        joined = false;
        return new Response(null, { status: 204 });
      }
      if (call.url === "/api/v1/patients/me/waitlist") return json(200, joined ? [entry] : []);
      return json(404, {});
    });
    const user = userEvent.setup();
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter initialEntries={["/doctors/d1/book"]}>
          <Routes>
            <Route path="/doctors/:doctorId/book" element={<BookingPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    fireEvent.change(await screen.findByLabelText("Day"), { target: { value: iso } });
    await user.click(screen.getByRole("button", { name: /Notify me if a time opens/ }));

    expect(await screen.findByRole("status")).toHaveTextContent("We'll tell you if a time opens on");
    expect(calls.find((c) => c.method === "POST")?.body).toEqual({ date: iso });
    const list = await screen.findByRole("list", { name: "Days you are waiting for" });
    await user.click(within(list).getByRole("button", { name: "Stop waiting" }));

    await waitFor(() => expect(calls.some((c) => c.method === "DELETE" && c.url.endsWith(`?date=${iso}`))).toBe(true));
    await waitFor(() => expect(screen.queryByRole("list", { name: "Days you are waiting for" })).not.toBeInTheDocument());
  });

  it("can book a video call instead of a clinic visit", async () => {
    const user = userEvent.setup();
    const { posts } = renderPage(() => json(201, { ...APPOINTMENT, visitType: "VIDEO" }));

    const [first] = await screen.findAllByRole("button", { pressed: false });
    await user.click(first!);
    expect(screen.getByRole("radio", { name: "At the clinic" })).toBeChecked();
    await user.click(screen.getByRole("radio", { name: "Video call" }));
    await user.click(screen.getByRole("button", { name: /^Confirm/ }));

    await waitFor(() => expect(posts()).toHaveLength(1));
    expect(posts()[0]!.body).toMatchObject({ visitType: "VIDEO" });
  });

  it("lays the times out by day and by part of the day, and says where the clinic is", async () => {
    const user = userEvent.setup();
    const local = (day: number, hour: number) => new Date(2030, 0, day, hour, 0).toISOString();
    mockFetch((call) => {
      if (call.url.includes("/slots")) {
        return json(200, [
          { id: "a", startsAt: local(7, 9), endsAt: local(7, 10) },
          { id: "b", startsAt: local(7, 14), endsAt: local(7, 15) },
          { id: "c", startsAt: local(7, 18), endsAt: local(7, 19) },
          { id: "d", startsAt: local(8, 10), endsAt: local(8, 11) },
        ]);
      }
      if (call.url === "/api/v1/doctors/d1") {
        return json(200, { id: "d1", fullName: "Dr. Anjali Rao", specialization: "Cardiology", consultationFee: 1200, yearsExperience: 14,
          bio: null, nextSlots: [], clinicName: "Rao Heart Clinic", clinicAddress: "12, 100 Feet Road", clinicLatitude: 12.97, clinicLongitude: 77.64 });
      }
      return json(404, {});
    });
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter initialEntries={["/doctors/d1/book"]}>
          <Routes>
            <Route path="/doctors/:doctorId/book" element={<BookingPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByText("Rao Heart Clinic")).toBeInTheDocument();
    const days = screen.getByRole("tablist", { name: "Days with open times" });
    expect(within(days).getAllByRole("tab")).toHaveLength(2);
    expect(within(days).getAllByRole("tab")[0]).toHaveTextContent("3 times");
    expect(screen.getByRole("heading", { name: "Morning" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Afternoon" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Evening" })).toBeInTheDocument();

    // The other day has only a morning time.
    await user.click(within(days).getAllByRole("tab")[1]!);
    expect(screen.queryByRole("heading", { name: "Afternoon" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { pressed: false }));
    expect(screen.getByLabelText("What brings you in?")).toBeInTheDocument();
  });

  it("shows what patients said, with stars and a first name only", async () => {
    mockFetch((call) => {
      if (call.url.includes("/slots")) return json(200, SLOTS);
      if (call.url.endsWith("/reviews")) {
        return json(200, [{ rating: 4, comment: "Kind and clear", reviewer: "Meera N.", createdAt: "2030-01-01T00:00:00Z" }]);
      }
      return json(404, {});
    });
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter initialEntries={["/doctors/d1/book"]}>
          <Routes>
            <Route path="/doctors/:doctorId/book" element={<BookingPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByRole("heading", { name: "What patients said" })).toBeInTheDocument();
    expect(screen.getByLabelText("4 out of 5")).toBeInTheDocument();
    expect(screen.getByText("Kind and clear")).toBeInTheDocument();
    expect(screen.getByText("Meera N.")).toBeInTheDocument();
  });

  it("moving a visit: no reason box, one confirm, and the move endpoint", async () => {
    const user = userEvent.setup();
    const calls = mockFetch((call) => {
      if (call.url.includes("/slots")) return json(200, SLOTS);
      if (call.url === "/api/v1/appointments/a1/reschedule") {
        return json(200, { ...APPOINTMENT, id: "appt-2", slotId: "slot-2", scheduledAt: SLOTS[1]!.startsAt, rescheduledFrom: "a1" });
      }
      return json(404, {});
    });
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter initialEntries={["/doctors/d1/book?move=a1&slot=slot-2"]}>
          <Routes>
            <Route path="/doctors/:doctorId/book" element={<BookingPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByRole("heading", { name: "Choose a new time" })).toBeInTheDocument();
    expect(screen.queryByLabelText("What brings you in?")).not.toBeInTheDocument();
    await user.click(await screen.findByRole("button", { name: /^Move to/ }));

    expect(await screen.findByRole("status")).toHaveTextContent("Visit moved to");
    const move = calls.find((c) => c.method === "POST");
    expect(move?.url).toBe("/api/v1/appointments/a1/reschedule");
    expect(move?.body).toEqual({ slotId: "slot-2" });
  });

  it("moving to a time someone just took says the visit is unchanged", async () => {
    const user = userEvent.setup();
    mockFetch((call) => {
      if (call.url.includes("/slots")) return json(200, SLOTS);
      return json(409, { code: "SLOT_ALREADY_BOOKED", detail: "This slot was just booked by someone else." });
    });
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter initialEntries={["/doctors/d1/book?move=a1&slot=slot-1"]}>
          <Routes>
            <Route path="/doctors/:doctorId/book" element={<BookingPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await user.click(await screen.findByRole("button", { name: /^Move to/ }));
    expect(await screen.findByRole("status")).toHaveTextContent("Your visit is unchanged");
  });

  it("starts with the time tapped on the directory card chosen, or says it has gone", async () => {
    renderPage(() => json(201, APPOINTMENT), "/doctors/d1/book?slot=slot-2");
    await screen.findByLabelText("What brings you in?");
    const chosen = screen.getAllByRole("button", { pressed: true });
    expect(chosen).toHaveLength(1);
    expect(screen.getByRole("button", { name: /^Confirm/ })).toBeInTheDocument();
  });

  it("says so when the tapped time was taken meanwhile", async () => {
    renderPage(() => json(201, APPOINTMENT), "/doctors/d1/book?slot=gone");
    expect(await screen.findByRole("status")).toHaveTextContent("That time has just been taken");
    expect(screen.queryByLabelText("What brings you in?")).not.toBeInTheDocument();
  });

  it("starts the reason from what the visitor typed in the body guide, and forgets it once booked", async () => {
    sessionStorage.setItem("medicity.visitNote", "My back tooth hurts when I chew");
    const user = userEvent.setup();
    const { posts } = renderPage(() => json(201, APPOINTMENT));

    const [first] = await screen.findAllByRole("button", { pressed: false });
    await user.click(first!);
    expect(screen.getByLabelText("What brings you in?")).toHaveValue("My back tooth hurts when I chew");
    await user.click(screen.getByRole("button", { name: /^Confirm/ }));

    await waitFor(() => expect(posts()).toHaveLength(1));
    expect(posts()[0]!.body).toMatchObject({ reason: "My back tooth hurts when I chew" });
    await waitFor(() => expect(sessionStorage.getItem("medicity.visitNote")).toBeNull());
  });

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
