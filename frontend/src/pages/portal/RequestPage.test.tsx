import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import type { Comparison } from "../../api/types";
import { json, mockFetch } from "../../test/fetchMock";
import { RequestPage } from "./RequestPage";

const OMEPRAZOLE = { medicineId: "m1", name: "Omeprazole", genericName: "Omeprazole", strength: "20mg", form: "CAPSULE", quantity: 14, substitutionAllowed: true };

const COMPARISON: Comparison = {
  id: "r1",
  status: "OPEN",
  createdAt: "2030-01-01T10:00:00Z",
  expiresAt: "2030-01-01T16:00:00Z",
  prescriptionId: "p1",
  doctorName: "Dr. Anjali Rao",
  diagnosis: "Reflux",
  storesAsked: 3,
  radiusM: 3000,
  items: [OMEPRAZOLE],
  stores: [
    {
      storeId: "s1", name: "Lakshmi Medical Stores", addressLine: "CMH Road", phone: "+919876500303",
      distanceM: 987, openNow: true, holdHours: 4, answered: true, note: "Omez is the same medicine, and cheaper.",
      answeredAt: "2030-01-01T10:05:00Z",
      lines: [{ medicineId: "m1", availability: "YES", quantityAvailable: 14, unitPrice: 4.2, substituteMedicineId: "m2", substituteName: "Omez", substituteStrength: "20mg" }],
      medicinesAvailable: 1, complete: true, total: 58.8, cheapestComplete: true, nearestComplete: false,
    },
    {
      storeId: "s2", name: "Nightingale 24x7 Chemists", addressLine: "Old Airport Road", phone: "+919876500304",
      distanceM: 1466, openNow: true, holdHours: 3, answered: true, note: null, answeredAt: "2030-01-01T10:06:00Z",
      lines: [{ medicineId: "m1", availability: "PARTIAL", quantityAvailable: 10, unitPrice: 5.8, substituteMedicineId: null, substituteName: null, substituteStrength: null }],
      medicinesAvailable: 1, complete: false, total: 58, cheapestComplete: false, nearestComplete: false,
    },
    {
      storeId: "s3", name: "Sri Sai Medicals", addressLine: "100 Feet Road", phone: "+919876500301",
      distanceM: 292, openNow: false, holdHours: 3, answered: false, note: null, answeredAt: null, lines: [],
      medicinesAvailable: 0, complete: false, total: null, cheapestComplete: false, nearestComplete: false,
    },
  ],
};

function renderPage() {
  mockFetch(() => json(200, COMPARISON));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/portal/requests/r1"]}>
        <Routes>
          <Route path="/portal/requests/:requestId" element={<RequestPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("RequestPage", () => {
  it("shows stores in the server's order, with what each has and what it costs", async () => {
    renderPage();

    const cards = await screen.findAllByRole("listitem");
    expect(cards.map((c) => within(c).getByRole("heading").textContent)).toEqual([
      "Lakshmi Medical Stores",
      "Nightingale 24x7 Chemists",
      "Sri Sai Medicals",
    ]);

    const best = within(cards[0]!);
    expect(best.getByText("Has everything")).toBeInTheDocument();
    expect(best.getByText("Cheapest")).toBeInTheDocument();
    expect(best.getByText(/as Omez 20mg \(same medicine\)/)).toBeInTheDocument();
    expect(best.getByText("In stock")).toBeInTheDocument();

    const partial = within(cards[1]!);
    expect(partial.getByText("10 of 14")).toBeInTheDocument();
    expect(partial.getByText(/for what they have/)).toBeInTheDocument();

    expect(within(cards[2]!).getByText("Not answered yet.")).toBeInTheDocument();
    expect(screen.getByText(/2 of 3 stores answered/)).toBeInTheDocument();
  });
});
