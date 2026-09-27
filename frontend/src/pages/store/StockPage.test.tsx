import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { Medicine, StockView } from "../../api/types";
import { json, mockFetch } from "../../test/fetchMock";
import { renderPage } from "../../test/renderPage";
import { StockPage } from "./StockPage";

const medicine = (id: string, name: string, strength: string): Medicine => ({
  id, name, genericName: name.toLowerCase(), form: "TABLET", strength, quantityOnHand: 0, lowStock: false,
});
const CATALOGUE = {
  content: [medicine("m1", "Glycomet", "500mg"), medicine("m2", "Azithral", "250mg")],
  totalElements: 2, totalPages: 1, number: 0,
};
const STALE: StockView = {
  autoAnswer: true,
  updatedAt: "2030-01-01T09:00:00Z",
  fresh: false,
  items: [{ medicineId: "m1", name: "Glycomet", strength: "500mg", form: "TABLET", quantity: 40, unitPrice: 1.6 }],
};

describe("StockPage", () => {
  it("sends the whole list, leaving half-filled rows out, and says stale stock never answers by itself", async () => {
    const calls = mockFetch(({ url, method }) => {
      if (url.startsWith("/api/v1/pharmacy")) return json(200, CATALOGUE);
      return json(200, method === "PUT" ? { ...STALE, fresh: true } : STALE);
    });
    renderPage(<StockPage />);

    expect(await screen.findByText(/older than a day never answers automatically/)).toBeInTheDocument();
    expect(screen.getByLabelText("Glycomet 500mg in stock")).toHaveValue(40);

    // A quantity without a price is not sent.
    await userEvent.type(screen.getByLabelText("Azithral 250mg in stock"), "12");
    await userEvent.click(screen.getByRole("button", { name: "Send stock" }));

    expect(await screen.findByText(/Fresh: automatic answers are on/)).toBeInTheDocument();
    expect(calls.find((c) => c.method === "PUT")?.body).toEqual({
      items: [{ medicineId: "m1", quantity: 40, unitPrice: 1.6 }],
    });
  });

  it("turns automatic answers off", async () => {
    const calls = mockFetch(({ url, method }) => {
      if (url.startsWith("/api/v1/pharmacy")) return json(200, CATALOGUE);
      return json(200, method === "PUT" ? { ...STALE, autoAnswer: false } : STALE);
    });
    renderPage(<StockPage />);
    const toggle = await screen.findByRole("checkbox", { name: /Answer questions automatically/ });
    expect(toggle).toBeChecked();

    await userEvent.click(toggle);

    await screen.findByRole("checkbox", { name: /Answer questions automatically/, checked: false });
    expect(calls.at(-1)).toMatchObject({ url: "/api/v1/stores/me/auto-answer", method: "PUT", body: { enabled: false } });
  });
});
