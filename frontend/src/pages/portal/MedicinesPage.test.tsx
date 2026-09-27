import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import type { Course } from "../../api/types";
import { json, mockFetch } from "../../test/fetchMock";
import { MedicinesPage } from "./MedicinesPage";

const base = {
  prescriptionItemId: "i1",
  prescriptionId: "p1",
  strength: "20mg",
  frequency: "Once daily, before breakfast",
  issuedAt: "2030-01-01T05:00:00Z",
};

const COURSES: Course[] = [
  {
    ...base, medicine: "Omeprazole", durationDays: 28, startedAt: "2030-01-02T06:00:00Z",
    startedWhere: "Sri Sai Medicals", firstDay: "2030-01-02", lastDay: "2030-01-29", daysLeft: 3, ongoing: true,
    status: "RUNNING_OUT",
  },
  {
    ...base, prescriptionItemId: "i2", prescriptionId: "p2", medicine: "Azithromycin", strength: "250mg",
    frequency: "Once daily", durationDays: 5, startedAt: "2030-01-24T06:00:00Z", startedWhere: "Green Cross",
    firstDay: "2030-01-24", lastDay: "2030-01-28", daysLeft: 1, ongoing: false, status: "TAKING",
  },
];

describe("MedicinesPage", () => {
  it("offers to ask again for a long-term medicine running out, and tells a course to finish", async () => {
    mockFetch(() => json(200, COURSES));
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter>
          <MedicinesPage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    const runningOut = (await screen.findByRole("heading", { name: "Running out soon" })).closest("section")!;
    expect(within(runningOut).getByText(/3 days left/)).toBeInTheDocument();
    expect(within(runningOut).getByRole("link", { name: "Ask the chemists again" })).toHaveAttribute(
      "href",
      "/portal/prescriptions?ask=p1",
    );

    const taking = screen.getByRole("heading", { name: "Taking now" }).closest("section")!;
    expect(within(taking).getByText(/Last day tomorrow: finish the course/)).toBeInTheDocument();
    expect(within(taking).queryByRole("link")).toBeNull();
  });
});
