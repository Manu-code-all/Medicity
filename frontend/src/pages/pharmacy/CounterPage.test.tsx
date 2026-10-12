import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { PharmacyPrescription } from "../../api/types";
import { json, mockFetch } from "../../test/fetchMock";
import { renderPage } from "../../test/renderPage";
import { CounterPage } from "./CounterPage";

const RX: PharmacyPrescription = {
  id: "rx-1",
  reference: "1a2b3c4d",
  patientName: "Meera Nair",
  doctorName: "Dr. Rao",
  diagnosis: "Migraine",
  issuedAt: "2030-01-01T09:00:00Z",
  dispensedAt: null,
  canDispense: true,
  items: [
    { medicineId: "m1", medicine: "Paracetamol", strength: "500mg", quantity: 10, dosage: "1 tablet", frequency: "twice daily", durationDays: 5, quantityOnHand: 90 },
  ],
};

describe("CounterPage", () => {
  it("looks a prescription up by reference and dispenses it", async () => {
    const calls = mockFetch(({ url, method }) => {
      if (method === "POST") {
        return json(200, { prescriptionId: "rx-1", dispensedAt: "2030-01-01T10:00:00Z", items: [{ medicine: "Paracetamol", strength: "500mg", quantity: 10, remainingStock: 80 }] });
      }
      expect(url).toBe("/api/v1/pharmacy/prescriptions?q=1a2b3c4d");
      return json(200, [RX]);
    });
    renderPage(<CounterPage />);

    await userEvent.type(screen.getByLabelText("Reference or patient name"), "1a2b3c4d");
    await userEvent.click(screen.getByRole("button", { name: "Find" }));

    expect(await screen.findByRole("heading", { name: "Meera Nair" })).toBeInTheDocument();
    expect(screen.getByText(/Paracetamol/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Dispense all" }));

    expect(await screen.findByText(/Remaining: Paracetamol 80/)).toBeInTheDocument();
    expect(calls.some((c) => c.method === "POST" && c.url === "/api/v1/pharmacy/prescriptions/rx-1/dispense")).toBe(true);
  });

  it("will not offer to dispense when a medicine is short, or when it is already dispensed", async () => {
    const short = { ...RX, items: [{ ...RX.items[0]!, quantityOnHand: 3 }] };
    const done = { ...RX, id: "rx-2", patientName: "Arjun", dispensedAt: "2030-01-01T10:00:00Z", canDispense: false };
    mockFetch(() => json(200, [short, done]));
    renderPage(<CounterPage />);

    await userEvent.type(screen.getByLabelText("Reference or patient name"), "meera");
    await userEvent.click(screen.getByRole("button", { name: "Find" }));

    expect(await screen.findByText(/Not enough stock for Paracetamol/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Dispense all" })).toBeDisabled();
    expect(screen.getByText(/Dispensed/)).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "Dispense all" })).toHaveLength(1);
  });

  it("says when nothing matches", async () => {
    mockFetch(() => json(200, []));
    renderPage(<CounterPage />);

    await userEvent.type(screen.getByLabelText("Reference or patient name"), "zzzzzz");
    await userEvent.click(screen.getByRole("button", { name: "Find" }));

    expect(await screen.findByRole("heading", { name: "No prescription found" })).toBeInTheDocument();
  });

  it("shows the server's message when dispensing is refused", async () => {
    mockFetch(({ method }) =>
      method === "POST"
        ? json(409, { code: "ALREADY_DISPENSED", title: "Conflict", status: 409, detail: "This prescription has already been dispensed" })
        : json(200, [RX]),
    );
    renderPage(<CounterPage />);

    await userEvent.type(screen.getByLabelText("Reference or patient name"), "1a2b3c4d");
    await userEvent.click(screen.getByRole("button", { name: "Find" }));
    await userEvent.click(await screen.findByRole("button", { name: "Dispense all" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/already been dispensed/);
  });
});
