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

  it("an upcoming video visit offers to join the call", async () => {
    mockFetch(({ url }) => {
      if (url.includes("/prescriptions")) return json(200, []);
      return json(200, page(url.includes("scope=upcoming") ? [{ ...UPCOMING, visitType: "VIDEO" }] : []));
    });
    renderPage(<VisitsPage />);

    expect(await screen.findByText("Video")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Join video" })).toHaveAttribute("href", "/visits/a1/video");
  });

  it("a visit later today shows whether the doctor is running on time", async () => {
    const soon = new Date();
    soon.setMinutes(soon.getMinutes() + 5);
    const today = { ...UPCOMING, id: "t1", doctorId: "d1", scheduledAt: soon.toISOString(), endsAt: soon.toISOString() };
    const calls = mockFetch(({ url }) => {
      if (url.includes("/prescriptions")) return json(200, []);
      if (url.endsWith("/live-status")) {
        return json(200, { state: "RUNNING_LATE", delayMinutes: 25, visitInProgress: true, asOf: soon.toISOString() });
      }
      return json(200, page(url.includes("scope=upcoming") ? [today, UPCOMING] : []));
    });
    renderPage(<VisitsPage />);

    expect(await screen.findByText(/Doctor is running about 25 min behind/)).toBeInTheDocument();
    // Only today's visit asks; the one next year does not.
    expect(calls.filter((c) => c.url.endsWith("/live-status")).map((c) => c.url)).toEqual(["/api/v1/doctors/d1/live-status"]);
  });

  it("attaches a report to an upcoming visit", async () => {
    let attached = false;
    const calls = mockFetch(({ url, method }) => {
      if (url.endsWith("/attachments") && method === "POST") {
        attached = true;
        return json(201, {});
      }
      if (url.endsWith("/attachments")) {
        return json(200, attached
          ? [{ id: "x1", fileName: "report.pdf", contentType: "application/pdf", sizeBytes: 2048, note: null, uploadedAt: "2030-01-01T00:00:00Z" }]
          : []);
      }
      if (url.includes("/prescriptions")) return json(200, []);
      return json(200, page(url.includes("scope=upcoming") ? [UPCOMING] : []));
    });
    renderPage(<VisitsPage />);

    await userEvent.click(await screen.findByRole("button", { name: /Attach medical records/ }));
    const file = new File(["%PDF-1.4"], "report.pdf", { type: "application/pdf" });
    await userEvent.upload(screen.getByLabelText(/Add a report or earlier prescription/), file);

    expect(await screen.findByRole("list", { name: "Attached records" })).toHaveTextContent("report.pdf");
    const post = calls.find((c) => c.method === "POST");
    expect(post?.url).toBe("/api/v1/appointments/a1/attachments");
  });

  it("a visit that ended in the last 7 days offers free follow-up questions", async () => {
    const ended = new Date(Date.now() - 2 * 86_400_000).toISOString();
    const calls = mockFetch(({ url, method }) => {
      if (url.includes("/prescriptions")) return json(200, []);
      if (url.endsWith("/followups")) {
        return json(200, {
          messages: method === "POST" ? [{ id: "f1", sender: "PATIENT", body: "Is the dose right?", sentAt: ended }] : [],
          questionsLeft: method === "POST" ? 2 : 3, closesAt: ended, open: true, awaitingDoctor: method === "POST",
        });
      }
      return json(200, page(url.includes("scope=past") ? [{ ...PAST, scheduledAt: ended, endsAt: ended, reviewed: true }] : []));
    });
    renderPage(<VisitsPage />);
    await screen.findByText("Nothing booked");
    await userEvent.click(screen.getByRole("tab", { name: "History" }));

    await userEvent.click(await screen.findByRole("button", { name: /Free follow-up · 5 days left/ }));
    expect(await screen.findByText(/3 of 3 free questions left/)).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("Your question"), "Is the dose right?");
    await userEvent.click(screen.getByRole("button", { name: "Send question" }));

    expect(await screen.findByText(/2 of 3 free questions left/)).toBeInTheDocument();
    expect(calls.find((c) => c.method === "POST")?.url).toBe("/api/v1/appointments/a0/followups");
  });

  it("a completed visit can be rated from history, once", async () => {
    let reviewed = false;
    const calls = mockFetch(({ url, method }) => {
      if (method === "POST") {
        reviewed = true;
        return new Response(null, { status: 204 });
      }
      if (url.includes("/prescriptions")) return json(200, []);
      return json(200, page(url.includes("scope=past") ? [{ ...PAST, reviewed }] : []));
    });
    renderPage(<VisitsPage />);
    await screen.findByText("Nothing booked");
    await userEvent.click(screen.getByRole("tab", { name: "History" }));

    await userEvent.click(await screen.findByRole("button", { name: "Rate this visit" }));
    const send = screen.getByRole("button", { name: "Send review" });
    expect(send).toBeDisabled();
    await userEvent.click(screen.getByRole("radio", { name: /4 stars/ }));
    await userEvent.type(screen.getByLabelText("A few words (optional)"), "Kind and clear");
    await userEvent.click(send);

    expect(await screen.findByText("You reviewed this visit. Thank you.")).toBeInTheDocument();
    const post = calls.find((c) => c.method === "POST");
    expect(post?.url).toBe("/api/v1/appointments/a0/review");
    expect(post?.body).toEqual({ rating: 4, comment: "Kind and clear" });
  });

  it("an already reviewed visit offers no rating", async () => {
    mockFetch(({ url }) => {
      if (url.includes("/prescriptions")) return json(200, []);
      return json(200, page(url.includes("scope=past") ? [{ ...PAST, reviewed: true }] : []));
    });
    renderPage(<VisitsPage />);
    await screen.findByText("Nothing booked");
    await userEvent.click(screen.getByRole("tab", { name: "History" }));

    expect(await screen.findByText("You reviewed this visit. Thank you.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Rate this visit" })).not.toBeInTheDocument();
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
