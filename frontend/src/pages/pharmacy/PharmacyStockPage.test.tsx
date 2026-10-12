import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { json, mockFetch } from "../../test/fetchMock";
import { renderPage } from "../../test/renderPage";
import { PharmacyStockPage } from "./PharmacyStockPage";

const LOW = [{ medicineId: "m1", name: "Cetirizine", strength: "10mg", quantityOnHand: 3, reorderLevel: 10 }];

describe("PharmacyStockPage", () => {
  it("lists what is low and restocks it", async () => {
    const calls = mockFetch(({ url, method }) => {
      if (method === "POST") return json(200, { id: "m1", name: "Cetirizine", quantityOnHand: 203, lowStock: false });
      expect(url).toBe("/api/v1/pharmacy/stock/low");
      return json(200, LOW);
    });
    renderPage(<PharmacyStockPage />);

    expect(await screen.findByText("3 on hand, reorder at 10")).toBeInTheDocument();
    const button = screen.getByRole("button", { name: "Add stock" });
    expect(button).toBeDisabled();

    await userEvent.type(screen.getByLabelText("Received, Cetirizine 10mg"), "200");
    await userEvent.click(button);

    expect(await screen.findByText("Now 203 on hand.")).toBeInTheDocument();
    expect(calls.find((c) => c.method === "POST")).toMatchObject({
      url: "/api/v1/pharmacy/medicines/m1/restock",
      body: { quantity: 200 },
    });
  });

  it("says nothing is low, and can search for another medicine to restock", async () => {
    const calls = mockFetch(({ url }) =>
      url.includes("/medicines")
        ? json(200, { content: [{ id: "m2", name: "Azithral", genericName: "azithromycin", form: "TABLET", strength: "250mg", quantityOnHand: 40, lowStock: false }], totalElements: 1, totalPages: 1, number: 0 })
        : json(200, []),
    );
    renderPage(<PharmacyStockPage />);

    expect(await screen.findByRole("heading", { name: "Nothing is low" })).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("Medicine name"), "azi");
    await userEvent.click(screen.getByRole("button", { name: "Search" }));

    expect(await screen.findByText("40 on hand")).toBeInTheDocument();
    expect(calls.some((c) => c.url === "/api/v1/pharmacy/medicines?size=10&q=azi")).toBe(true);
  });
});
