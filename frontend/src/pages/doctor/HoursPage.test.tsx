import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { json, mockFetch } from "../../test/fetchMock";
import { renderPage } from "../../test/renderPage";
import { HoursPage } from "./HoursPage";

describe("HoursPage", () => {
  it("shows the saved week, counts visits per day, and sends only the days that are on", async () => {
    const calls = mockFetch(({ method, body }) =>
      method === "PUT"
        ? json(200, { hours: (body as { days: unknown[] }).days, slotsOpened: 36 })
        : json(200, [{ weekday: 1, startsAt: "10:00:00", endsAt: "12:00:00", slotMinutes: 30 }]),
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

  it("shows the server's reason when hours are refused", async () => {
    mockFetch(({ method }) =>
      method === "PUT"
        ? json(422, { code: "INVALID_HOURS", detail: "Each day must end after it starts" })
        : json(200, []),
    );
    renderPage(<HoursPage />);

    await userEvent.click(await screen.findByRole("checkbox", { name: "Monday" }));
    await userEvent.click(screen.getByRole("button", { name: "Save hours" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Each day must end after it starts");
  });
});
