import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import type { DoctorVisitDetail, Prescription } from "../../api/types";
import { json, mockFetch } from "../../test/fetchMock";
import { VisitPage } from "./VisitPage";

const MEDICINES = {
  content: [
    { id: "m6", name: "Omeprazole", genericName: "Omeprazole", form: "CAPSULE", strength: "20mg", quantityOnHand: 250, lowStock: false },
    { id: "m1", name: "Paracetamol", genericName: "Acetaminophen", form: "TABLET", strength: "500mg", quantityOnHand: 250, lowStock: false },
  ],
  totalElements: 2, totalPages: 1, number: 0,
};

const BOOKED: DoctorVisitDetail = {
  id: "v1", status: "BOOKED", scheduledAt: "2030-01-01T05:30:00Z", endsAt: "2030-01-01T06:00:00Z",
  reason: "Acidity after meals", cancelReason: null, started: true, prescription: null,
  patient: { id: "p1", fullName: "Meera Nair", age: 32, gender: "FEMALE", bloodGroup: "O+" },
};

const RX: Prescription = {
  id: "rx1", appointmentId: "v1", issuedAt: "2030-01-01T06:00:00Z", doctorName: "Dr. Anjali Rao",
  specialization: "Cardiology", diagnosis: "GERD", notes: "", revised: false, dispensedAt: null, collectedAt: null,
  collectedFrom: null, hasPhoto: false,
  items: [{
    medicineId: "m6", medicine: "Omeprazole", strength: "20mg", form: "CAPSULE", dosage: "20mg",
    frequency: "Once daily before breakfast", durationDays: 14, quantity: 14, genericName: "Omeprazole",
    substitutionAllowed: true,
  }],
};

function renderVisit() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>
      <MemoryRouter initialEntries={["/doctor/visits/v1"]}>
        <Routes>
          <Route path="/doctor/visits/:visitId" element={<VisitPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("VisitPage", () => {
  it("closes the visit, then writes a prescription line by line, allowing a cheaper brand", async () => {
    let visit: DoctorVisitDetail = BOOKED;
    const calls = mockFetch(({ url, method }) => {
      if (url.startsWith("/api/v1/pharmacy/medicines")) return json(200, MEDICINES);
      if (method === "POST" && url.endsWith("/complete")) {
        visit = { ...visit, status: "COMPLETED" };
        return json(200, visit);
      }
      if (method === "POST" && url.endsWith("/prescriptions")) {
        visit = { ...visit, prescription: RX };
        return json(201, visit);
      }
      return json(200, visit);
    });
    renderVisit();

    expect(await screen.findByRole("heading", { name: "Meera Nair" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Patient was seen" }));
    await userEvent.click(await screen.findByRole("button", { name: "Write prescription" }));

    await userEvent.type(await screen.findByLabelText("Diagnosis"), "GERD");
    await userEvent.selectOptions(screen.getByLabelText("Medicine"), "m6");
    await userEvent.type(screen.getByLabelText("Dose"), "20mg");
    await userEvent.type(screen.getByLabelText("How often"), "Once daily before breakfast");
    await userEvent.clear(screen.getByLabelText("Days"));
    await userEvent.type(screen.getByLabelText("Days"), "14");
    await userEvent.clear(screen.getByLabelText("Qty"));
    await userEvent.type(screen.getByLabelText("Qty"), "14");
    await userEvent.click(screen.getByRole("checkbox", { name: /Cheaper brand/ }));
    await userEvent.click(screen.getByRole("button", { name: "Issue prescription" }));

    expect(await screen.findByRole("heading", { name: "GERD" })).toBeInTheDocument();
    const issued = calls.find((c) => c.method === "POST" && c.url.endsWith("/prescriptions"));
    expect(issued?.url).toBe("/api/v1/doctors/me/visits/v1/prescriptions");
    expect(issued?.body).toMatchObject({
      diagnosis: "GERD",
      items: [{ medicineId: "m6", dosage: "20mg", frequency: "Once daily before breakfast", durationDays: 14, quantity: 14, substitutionAllowed: true }],
    });
  });

  it("a correction starts from the issued prescription and replaces it", async () => {
    const calls = mockFetch(({ url, method }) => {
      if (url.startsWith("/api/v1/pharmacy/medicines")) return json(200, MEDICINES);
      if (method === "POST") return json(201, { ...BOOKED, status: "COMPLETED", prescription: { ...RX, id: "rx2", revised: true, diagnosis: "GERD, mild" } });
      return json(200, { ...BOOKED, status: "COMPLETED", prescription: RX });
    });
    renderVisit();

    await userEvent.click(await screen.findByRole("button", { name: "Correct prescription" }));
    const diagnosis = await screen.findByLabelText("Diagnosis");
    expect(diagnosis).toHaveValue("GERD");
    expect(screen.getByLabelText("Medicine")).toHaveValue("m6");

    await userEvent.type(diagnosis, ", mild");
    await userEvent.click(screen.getByRole("button", { name: "Issue correction" }));

    expect(await screen.findByRole("heading", { name: "GERD, mild" })).toBeInTheDocument();
    expect(calls.find((c) => c.method === "POST")?.url).toBe("/api/v1/doctors/me/prescriptions/rx1/corrections");
  });

  it("when the patient cancelled meanwhile, says so and shows the visit as it now is", async () => {
    let cancelled = false;
    mockFetch(({ method }) => {
      if (method === "POST") {
        cancelled = true;
        return json(409, { code: "VISIT_NOT_OPEN", detail: "This visit was cancelled by the patient." });
      }
      return json(200, cancelled ? { ...BOOKED, status: "CANCELLED", cancelReason: "Feeling better" } : BOOKED);
    });
    renderVisit();

    await userEvent.click(await screen.findByRole("button", { name: "Patient was seen" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("cancelled by the patient");
    expect(await screen.findByText("Cancelled by patient: Feeling better")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Patient was seen" })).not.toBeInTheDocument();
  });
});
