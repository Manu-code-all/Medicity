import { fireEvent, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { json, mockFetch, type RecordedCall } from "../../test/fetchMock";
import { renderPage } from "../../test/renderPage";
import { HoursPage } from "./HoursPage";

/** The hours endpoints; leave answers from `leave`. */
function server(hours: unknown[], onPut?: (call: RecordedCall) => Response, leave: unknown[] = []) {
  return mockFetch((call) => {
    if (call.url.includes("/leave")) {
      if (call.method === "POST") return json(200, { ...(call.body as object), bookedVisits: 2 });
      return json(200, leave);
    }
    if (call.method === "PUT" && onPut) return onPut(call);
    return json(200, hours);
  });
}

describe("HoursPage", () => {
  it("shows the saved week, counts visits per day, and sends only the days that are on", async () => {
    const calls = server([{ weekday: 1, startsAt: "10:00:00", endsAt: "12:00:00", slotMinutes: 30 }], ({ body }) =>
      json(200, { hours: (body as { days: unknown[] }).days, slotsOpened: 36 }),
    );
    renderPage(<HoursPage />);

    expect(await screen.findByLabelText("Monday from")).toHaveValue("10:00");
    expect(screen.getByLabelText("Tuesday from")).toBeDisabled();
    expect(screen.getByText("4 appointments a week.")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("checkbox", { name: "Saturday" }));
    await userEvent.selectOptions(screen.getByLabelText("Saturday visit length"), "60");
    expect(screen.getByText("7 appointments a week.")).toBeInTheDocument();   // 4 + 10:00-13:00 at 60 min

    await userEvent.click(screen.getByRole("button", { name: "Save hours" }));

    expect(await screen.findByText(/36 appointments opened for the next four weeks/)).toBeInTheDocument();
    expect(calls.find((c) => c.method === "PUT")?.body).toEqual({
      days: [
        { weekday: 1, startsAt: "10:00", endsAt: "12:00", slotMinutes: 30 },
        { weekday: 6, startsAt: "10:00", endsAt: "13:00", slotMinutes: 60 },
      ],
    });
  });

  it("adds a second session after a lunch break, and sends both", async () => {
    const calls = server([{ weekday: 1, startsAt: "09:00:00", endsAt: "12:00:00", slotMinutes: 30 }], ({ body }) =>
      json(200, { hours: (body as { days: unknown[] }).days, slotsOpened: 40 }),
    );
    renderPage(<HoursPage />);

    await userEvent.click(await screen.findByRole("button", { name: "Add a session on Monday" }));
    // An hour after the morning ends, three hours long, same visit length.
    expect(screen.getByLabelText("Monday session 2 from")).toHaveValue("13:00");
    expect(screen.getByLabelText("Monday session 2 to")).toHaveValue("16:00");
    expect(screen.getByText("12 appointments a week.")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Save hours" }));
    await screen.findByText(/40 appointments opened/);
    expect(calls.find((c) => c.method === "PUT")?.body).toEqual({
      days: [
        { weekday: 1, startsAt: "09:00", endsAt: "12:00", slotMinutes: 30 },
        { weekday: 1, startsAt: "13:00", endsAt: "16:00", slotMinutes: 30 },
      ],
    });

    await userEvent.click(screen.getByRole("button", { name: "Remove Monday session 2" }));
    expect(screen.queryByLabelText("Monday session 2 from")).not.toBeInTheDocument();
  });

  it("shows the server's reason when hours are refused", async () => {
    server([], () => json(422, { code: "SESSIONS_OVERLAP", detail: "Two of the sessions on Monday overlap" }));
    renderPage(<HoursPage />);

    await userEvent.click(await screen.findByRole("checkbox", { name: "Monday" }));
    await userEvent.click(screen.getByRole("button", { name: "Save hours" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Two of the sessions on Monday overlap");
  });

  it("lists days off with the visits still booked, and marks a new one", async () => {
    const calls = server([], undefined, [{ day: "2031-03-14", note: "Conference", bookedVisits: 1 }]);
    renderPage(<HoursPage />);

    expect(await screen.findByText(/Conference/)).toBeInTheDocument();
    expect(screen.getByText(/1 visit is still booked that day/)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Day"), { target: { value: "2031-04-01" } });
    await userEvent.type(screen.getByLabelText(/Note/), "Family wedding");
    await userEvent.click(screen.getByRole("button", { name: "Mark day off" }));

    expect(await screen.findByText(/2 visits were already booked that day and are kept/)).toBeInTheDocument();
    expect(calls.find((c) => c.method === "POST")?.body).toEqual({ day: "2031-04-01", note: "Family wedding" });
  });
});
