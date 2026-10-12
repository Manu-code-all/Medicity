import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import type { PatientHistory } from "../../api/types";
import { json, mockFetch } from "../../test/fetchMock";
import { PatientHistoryPage } from "./PatientHistoryPage";

const HISTORY: PatientHistory = {
  patient: { id: "p1", fullName: "Meera Nair", age: 32, gender: "FEMALE", bloodGroup: "O+" },
  visits: [{
    id: "v1", status: "COMPLETED", scheduledAt: "2029-12-01T05:30:00Z", endsAt: "2029-12-01T06:00:00Z",
    reason: "Acidity", doctorId: "d1", doctorName: "Dr. Anjali Rao", specialization: "Cardiology",
    cancelledAt: null, cancelReason: null,
  }],
  prescriptions: [{
    id: "rx1", appointmentId: "v1", issuedAt: "2029-12-01T06:00:00Z", doctorName: "Dr. Anjali Rao",
    specialization: "Cardiology", diagnosis: "GERD", diagnosisCode: "K21.9", notes: null, revised: true,
    dispensedAt: null, collectedAt: null, collectedFrom: null, hasPhoto: false,
    items: [{
      medicineId: "m6", medicine: "Omeprazole", strength: "20mg", form: "CAPSULE", dosage: "20mg",
      frequency: "Once daily", durationDays: 14, quantity: 14, genericName: "Omeprazole", substitutionAllowed: false,
    }],
  }],
};

function renderHistory() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={["/doctor/patients/p1"]}>
        <Routes>
          <Route path="/doctor/patients/:patientId" element={<PatientHistoryPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("PatientHistoryPage", () => {
  it("shows the patient, their visits and their current prescriptions", async () => {
    const calls = mockFetch(() => json(200, HISTORY));
    renderHistory();

    expect(await screen.findByRole("heading", { name: "Meera Nair" })).toBeInTheDocument();
    expect(screen.getByText(/32 yrs · female · blood group O\+ · 1 visits/)).toBeInTheDocument();
    expect(screen.getByText("GERD")).toBeInTheDocument();
    expect(screen.getByText(/ICD-10 K21.9/)).toBeInTheDocument();
    expect(screen.getByText(/· revised/)).toBeInTheDocument();
    expect(screen.getByText("Once daily")).toBeInTheDocument();
    expect(screen.getByText(/recorded in the audit log/)).toBeInTheDocument();
    expect(calls[0]!.url).toBe("/api/v1/doctors/me/patients/p1/history");
  });

  it("explains a refusal for a patient who is not theirs", async () => {
    mockFetch(() => json(403, { detail: "Not your patient" }));
    renderHistory();
    expect(await screen.findByText("You can only view the history of your own patients.")).toBeInTheDocument();
  });

  it("shows a general message for any other failure", async () => {
    mockFetch(() => json(500, { detail: "boom" }));
    renderHistory();
    expect(await screen.findByText("Could not load this patient's history.")).toBeInTheDocument();
  });
});
