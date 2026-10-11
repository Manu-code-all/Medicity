import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import type { Prescription } from "../../api/types";
import { json, mockFetch } from "../../test/fetchMock";
import { PrescriptionsPage } from "./PrescriptionsPage";

const RX: Prescription = {
  id: "rx1", appointmentId: "v1", issuedAt: "2030-01-01T06:00:00Z", doctorName: "Dr. Anjali Rao",
  specialization: "Cardiology", diagnosis: "GERD", diagnosisCode: "K21.9", notes: "Avoid spicy food", revised: true,
  dispensedAt: null, collectedAt: null, collectedFrom: null, hasPhoto: false,
  items: [{
    medicineId: "m6", medicine: "Omeprazole", strength: "20mg", form: "CAPSULE", dosage: "20mg",
    frequency: "Once daily before breakfast", durationDays: 14, quantity: 14, genericName: "Omeprazole",
    substitutionAllowed: true,
  }],
};

function renderAt(path = "/portal/prescriptions") {
  Element.prototype.scrollIntoView = () => undefined;
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/portal/prescriptions" element={<PrescriptionsPage />} />
          <Route path="/portal/requests/:id" element={<p>Comparison opened</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("PrescriptionsPage", () => {
  it("lists a prescription with its medicines, revision badge, code and the doctor's notes", async () => {
    mockFetch(() => json(200, [RX]));
    renderAt();

    const card = (await screen.findByRole("heading", { name: "GERD" })).closest("article")!;
    expect(within(card).getByText(/ICD-10 K21.9/)).toBeInTheDocument();
    expect(within(card).getByText("Revised")).toBeInTheDocument();
    expect(within(card).getByText("Not yet dispensed")).toBeInTheDocument();
    expect(within(card).getByText("Once daily before breakfast")).toBeInTheDocument();
    expect(within(card).getByText("14 days")).toBeInTheDocument();
    expect(within(card).getAllByText("Cheaper brand OK").length).toBeGreaterThan(0);
    expect(within(card).getByText(/Avoid spicy food/)).toBeInTheDocument();
  });

  it("says where a prescription was collected", async () => {
    mockFetch(() => json(200, [{ ...RX, collectedAt: "2030-01-02T10:00:00Z", collectedFrom: "Sri Sai Medicals" }]));
    renderAt();
    expect(await screen.findByText(/Collected at Sri Sai Medicals/)).toBeInTheDocument();
  });

  it("shows an empty state, and an error when the list cannot load", async () => {
    mockFetch(() => json(200, []));
    renderAt();
    expect(await screen.findByRole("heading", { name: "No prescriptions yet" })).toBeInTheDocument();
  });

  it("shows an error when the list cannot load", async () => {
    mockFetch(() => json(500, { detail: "boom" }));
    renderAt();
    expect(await screen.findByText("Could not load your prescriptions.")).toBeInTheDocument();
  });

  it("asks the chemists nearby about the whole prescription and opens the comparison", async () => {
    const calls = mockFetch(({ method }) => (method === "POST" ? json(201, { id: "r9" }) : json(200, [RX])));
    renderAt();

    await userEvent.click(await screen.findByRole("button", { name: "Ask chemists nearby" }));
    await userEvent.click(screen.getByRole("button", { name: "Ask every chemist nearby" }));

    expect(await screen.findByText("Comparison opened")).toBeInTheDocument();
    const post = calls.find((c) => c.method === "POST")!;
    expect(post.url).toBe("/api/v1/patients/me/medicine-requests");
    expect(post.body).toMatchObject({ prescriptionId: "rx1", radiusM: 3000 });
  });

  it("can leave out a medicine, and cannot ask about none", async () => {
    const calls = mockFetch(({ method }) => (method === "POST" ? json(201, { id: "r9" }) : json(200, [{ ...RX, items: [
      RX.items[0]!, { ...RX.items[0]!, medicineId: "m1", medicine: "Paracetamol", strength: "500mg" },
    ] }])));
    renderAt();

    await userEvent.click(await screen.findByRole("button", { name: "Ask chemists nearby" }));
    await userEvent.click(screen.getByRole("checkbox", { name: /Omeprazole/ }));
    await userEvent.click(screen.getByRole("button", { name: "Ask every chemist nearby" }));
    await screen.findByText("Comparison opened");
    expect(calls.find((c) => c.method === "POST")!.body).toMatchObject({ medicineIds: ["m1"] });
  });

  it("opens the ask panel straight from a refill reminder link", async () => {
    mockFetch(() => json(200, [RX]));
    renderAt("/portal/prescriptions?ask=rx1");
    expect(await screen.findByRole("button", { name: "Ask every chemist nearby" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.getByRole("button", { name: "Ask chemists nearby" })).toBeInTheDocument();
  });
});
