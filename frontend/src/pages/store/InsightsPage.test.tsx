import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import type { StoreInsights } from "../../api/types";
import { json, mockFetch } from "../../test/fetchMock";
import { InsightsPage } from "./InsightsPage";

const INSIGHTS: StoreInsights = {
  from: "2030-01-01T00:00:00Z",
  to: "2030-01-08T00:00:00Z",
  summary: { questionsReceived: 4, answered: 3, answeredAutomatically: 0, reservations: 1, collected: 1, medianMinutesToAnswer: 6 },
  medicines: [
    {
      medicineId: "m2", name: "Azithromycin", strength: "250mg", form: "TABLET", asked: 2, patients: 2, units: 8,
      had: 0, partly: 0, saidNo: 2, unanswered: 0, wentElsewhere: 2, considerStocking: true,
    },
  ],
};

describe("InsightsPage", () => {
  it("shows the week's counts and points out what to consider stocking", async () => {
    mockFetch(() => json(200, INSIGHTS));
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter>
          <InsightsPage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByText("Azithromycin 250mg")).toBeInTheDocument();
    expect(screen.getByText("Consider stocking")).toBeInTheDocument();
    expect(screen.getByText("2 people · 8 units")).toBeInTheDocument();
    expect(screen.getByText("minutes to answer (median)").previousSibling).toHaveTextContent("6");
  });
});
