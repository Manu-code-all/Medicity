import { fireEvent, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { json, mockFetch } from "../../test/fetchMock";
import { renderPage } from "../../test/renderPage";
import { ClinicReportPage } from "./ClinicReportPage";

const row = (date: string, n: number) => ({
  date, scheduled: n, completed: n - 1, noShows: 1, cancellations: 2, walkIns: 3, walkInsSeen: 2, bookedByDesk: 1,
});

describe("ClinicReportPage", () => {
  it("asks for the last week by default and lists each day with a total", async () => {
    const calls = mockFetch(() =>
      json(200, {
        from: "2030-01-01", to: "2030-01-02", timezone: "Asia/Kolkata",
        days: [row("2030-01-01", 5), row("2030-01-02", 7)],
        totals: row("2030-01-01", 12),
      }),
    );
    renderPage(<ClinicReportPage />);

    expect(await screen.findByRole("row", { name: /Total/ })).toHaveTextContent("12");
    expect(screen.getAllByRole("row")).toHaveLength(1 + 2 + 1);
    expect(screen.getByText(/Asia\/Kolkata/)).toBeInTheDocument();
    expect(calls[0]!.url).toMatch(/^\/api\/v1\/clinics\/me\/report\?from=\d{4}-\d\d-\d\d&to=\d{4}-\d\d-\d\d$/);
  });

  it("asks again for a new range and shows the server's refusal", async () => {
    const calls = mockFetch(({ url }) =>
      url.includes("from=2020-01-01")
        ? json(422, { status: 422, code: "INVALID_RANGE", detail: "Choose at most 92 days" })
        : json(200, { from: "x", to: "y", timezone: "Asia/Kolkata", days: [], totals: row("2030-01-01", 0) }),
    );
    renderPage(<ClinicReportPage />);
    await screen.findByRole("row", { name: /Total/ });

    const from = screen.getByLabelText("From");
    fireEvent.change(from, { target: { value: "2020-01-01" } });

    expect(await screen.findByRole("alert")).toHaveTextContent("Choose at most 92 days");
    expect(calls.some((c) => c.url.includes("from=2020-01-01"))).toBe(true);
  });
});
