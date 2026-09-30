import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { DoctorVisitSummary } from "../../api/types";
import { AuthContext, type AuthContextValue } from "../../auth/context";
import { json, mockFetch } from "../../test/fetchMock";
import { renderPage } from "../../test/renderPage";
import { SchedulePage } from "./SchedulePage";

const PATIENT = { id: "p1", fullName: "Meera Nair", age: 32, gender: "FEMALE" as const, bloodGroup: "O+" };
const hoursFromNow = (h: number) => new Date(Date.now() + h * 3_600_000).toISOString();

const VISITS: DoctorVisitSummary[] = [
  { id: "v1", status: "BOOKED", scheduledAt: hoursFromNow(-1), endsAt: hoursFromNow(-0.5), reason: "Acidity", patient: PATIENT, prescriptionId: null, dispensedAt: null },
  { id: "v2", status: "COMPLETED", scheduledAt: hoursFromNow(-3), endsAt: hoursFromNow(-2.5), reason: null, patient: { ...PATIENT, id: "p2", fullName: "Arjun Mehta" }, prescriptionId: "rx1", dispensedAt: hoursFromNow(-2) },
];

function renderSchedule() {
  const value = { session: { userId: "u1", fullName: "Dr. Anjali Rao", role: "DOCTOR" } } as AuthContextValue;
  return renderPage(
    <AuthContext.Provider value={value}>
      <SchedulePage />
    </AuthContext.Provider>,
  );
}

describe("SchedulePage", () => {
  it("lists the day's visits, flags one waiting to be closed, and links each to its visit", async () => {
    mockFetch(() => json(200, VISITS));
    renderSchedule();

    // Once in the "needs closing" tile that opens the day, once on the line.
    expect(await screen.findAllByText("Meera Nair")).toHaveLength(2);
    expect(screen.getByText(/2 visits · 1 waiting to be closed/)).toBeInTheDocument();
    expect(screen.getByText("Waiting to be closed")).toBeInTheDocument();
    expect(screen.getByText("Prescribed · dispensed")).toBeInTheDocument();
    for (const link of screen.getAllByRole("link", { name: /Meera Nair/ })) {
      expect(link).toHaveAttribute("href", "/doctor/visits/v1");
    }
    expect(screen.getByText("Needs closing")).toBeInTheDocument();
  });

  it("asks for exactly the next day, midnight to midnight, when moving forward", async () => {
    const calls = mockFetch(() => json(200, []));
    renderSchedule();
    await screen.findByText("No visits");

    await userEvent.click(screen.getByRole("button", { name: "Next day" }));
    await screen.findByRole("button", { name: "Today" });

    const next = new URL(`http://x${calls.at(-1)!.url}`).searchParams;
    const from = new Date(next.get("from")!);
    const to = new Date(next.get("to")!);
    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    expect(from.getDate()).toBe(tomorrow.getDate());
    expect(from.getHours()).toBe(0);
    expect(to.getTime() - from.getTime()).toBeGreaterThanOrEqual(23 * 3_600_000);
  });
});
