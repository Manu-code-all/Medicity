import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { RequestSummary } from "../../api/types";
import { json, mockFetch } from "../../test/fetchMock";
import { renderPage } from "../../test/renderPage";
import { RequestsPage } from "./RequestsPage";

const ROW: RequestSummary = {
  id: "r1", status: "RESERVED", createdAt: "2030-01-01T10:00:00Z", expiresAt: "2030-01-01T16:00:00Z",
  prescriptionId: "p1", diagnosis: "Reflux", doctorName: "Dr. Anjali Rao", medicines: 2, storesAsked: 5, answers: 3,
};

describe("RequestsPage", () => {
  it("lists each question with its status, medicines and how many chemists answered", async () => {
    mockFetch(() => json(200, [ROW, { ...ROW, id: "r2", status: "OPEN", medicines: 1, answers: 0, diagnosis: "Fever" }]));
    renderPage(<RequestsPage />);

    const first = (await screen.findByText("Reflux")).closest("a")!;
    expect(first).toHaveAttribute("href", "/portal/requests/r1");
    expect(first).toHaveTextContent("2 medicines · Dr. Anjali Rao");
    expect(first).toHaveTextContent("Reserved");
    expect(first).toHaveTextContent("3 of 5 answered");

    const second = screen.getByText("Fever").closest("a")!;
    expect(second).toHaveTextContent("1 medicine ·");
    expect(second).toHaveTextContent("Open");
  });

  it("points at the prescriptions when nothing was asked yet", async () => {
    mockFetch(() => json(200, []));
    renderPage(<RequestsPage />);
    expect(await screen.findByRole("heading", { name: "No questions yet" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Go to prescriptions" })).toHaveAttribute("href", "/portal/prescriptions");
  });

  it("says so when the questions cannot load", async () => {
    mockFetch(() => json(500, { detail: "boom" }));
    renderPage(<RequestsPage />);
    expect(await screen.findByText("Could not load your questions.")).toBeInTheDocument();
  });
});
