import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { Visit } from "../../api/types";
import { json, mockFetch } from "../../test/fetchMock";
import { renderPage } from "../../test/renderPage";
import { VisitsPage } from "./VisitsPage";

const page = (content: Visit[]) => ({ content, totalElements: content.length, totalPages: 1, number: 0 });

const UPCOMING: Visit = {
  id: "a1", status: "BOOKED", scheduledAt: "2031-03-04T05:30:00Z", endsAt: "2031-03-04T06:00:00Z",
  reason: "Knee pain", doctorId: "d6", doctorName: "Dr. Rohan Shetty", specialization: "Orthopaedics",
  cancelledAt: null, cancelReason: null,
};
const PAST: Visit = {
  ...UPCOMING, id: "a0", status: "COMPLETED", scheduledAt: "2020-03-04T05:30:00Z", endsAt: "2020-03-04T06:00:00Z",
};

describe("VisitsPage", () => {
  it("cancels an upcoming visit only after the patient confirms, then refreshes the list", async () => {
    let cancelled = false;
    const calls = mockFetch(({ url, method }) => {
      if (method === "POST") {
        cancelled = true;
        return json(200, { ...UPCOMING, status: "CANCELLED" });
      }
      if (url.includes("/prescriptions")) return json(200, []);
      return json(200, page(cancelled ? [] : [UPCOMING]));
    });
    const confirm = vi.spyOn(window, "confirm").mockReturnValueOnce(false).mockReturnValueOnce(true);
    renderPage(<VisitsPage />);

    expect(await screen.findByText("Dr. Rohan Shetty")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Change time" })).toHaveAttribute("href", "/doctors/d6/book?move=a1");
    const cancel = screen.getByRole("button", { name: "Cancel visit" });

    await userEvent.click(cancel);
    expect(calls.some((c) => c.method === "POST")).toBe(false);

    await userEvent.click(cancel);
    expect(await screen.findByText("Nothing booked")).toBeInTheDocument();
    expect(confirm).toHaveBeenCalledTimes(2);
    expect(calls.find((c) => c.method === "POST")).toMatchObject({
      url: "/api/v1/appointments/a1/cancel",
      body: { reason: "Cancelled by patient" },
    });
  });

  it("history offers no cancel button, and a visit with a prescription links to it", async () => {
    mockFetch(({ url }) => {
      if (url.includes("/prescriptions")) return json(200, [{ id: "rx9", appointmentId: "a0" }]);
      return json(200, page(url.includes("scope=past") ? [PAST] : []));
    });
    renderPage(<VisitsPage />);
    await screen.findByText("Nothing booked");

    await userEvent.click(screen.getByRole("tab", { name: "History" }));

    expect(await screen.findByText("Completed")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Cancel visit" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Change time" })).not.toBeInTheDocument();
    expect(await screen.findByRole("link", { name: "View prescription" })).toHaveAttribute(
      "href",
      "/portal/prescriptions#rx-rx9",
    );
  });

  it("shows the server's reason when a cancellation is refused", async () => {
    mockFetch(({ url, method }) => {
      if (method === "POST") return json(422, { code: "TOO_LATE_TO_CANCEL", detail: "Visits can be cancelled up to 2 hours before." });
      if (url.includes("/prescriptions")) return json(200, []);
      return json(200, page([UPCOMING]));
    });
    vi.spyOn(window, "confirm").mockReturnValue(true);
    renderPage(<VisitsPage />);

    await userEvent.click(await screen.findByRole("button", { name: "Cancel visit" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("up to 2 hours before");
  });
});
