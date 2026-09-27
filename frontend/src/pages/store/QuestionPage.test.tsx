import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import type { StoreView } from "../../api/types";
import { json, mockFetch } from "../../test/fetchMock";
import { QuestionPage } from "./QuestionPage";

const VIEW: StoreView = {
  id: "r1",
  status: "OPEN",
  createdAt: "2030-01-01T10:00:00Z",
  expiresAt: "2030-01-01T16:00:00Z",
  patientName: "Meera N.",
  distanceM: 292,
  prescription: {
    doctorName: "Dr. Anjali Rao",
    specialization: "Cardiology",
    doctorRegistration: "KA-CARD-10041",
    issuedAt: "2029-12-20T05:20:00Z",
    revised: false,
    hospitalDispensedAt: null,
    hasPhoto: false,
  },
  items: [
    {
      medicineId: "m1", name: "Omeprazole", genericName: "Omeprazole", strength: "20mg", form: "CAPSULE",
      quantity: 14, substitutionAllowed: true, dosage: "20mg", frequency: "Once daily, before breakfast",
      durationDays: 14, equivalents: [{ id: "m2", name: "Omez", strength: "20mg", listPrice: 42 }],
    },
    {
      medicineId: "m3", name: "Cetirizine", genericName: "Cetirizine", strength: "10mg", form: "TABLET",
      quantity: 5, substitutionAllowed: false, dosage: "10mg", frequency: "At night", durationDays: 5, equivalents: [],
    },
  ],
  myStatus: "PENDING",
  myNote: null,
  answeredAt: null,
  myAnswer: [],
};

describe("QuestionPage", () => {
  it("shows who prescribed it, and sends one line per medicine with partly, price and another brand", async () => {
    const calls = mockFetch(({ method }) => json(200, method === "POST" ? { ...VIEW, myStatus: "ANSWERED" } : VIEW));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={["/store/requests/r1"]}>
          <Routes>
            <Route path="/store/requests/:requestId" element={<QuestionPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByText(/Issued in Medicity by the prescribing doctor/)).toBeInTheDocument();
    expect(screen.getByText(/Reg. no. KA-CARD-10041/)).toBeInTheDocument();
    // Only the medicine the doctor allowed a substitute for offers one.
    expect(screen.getAllByRole("combobox")).toHaveLength(1);

    const user = userEvent.setup();
    await user.type(screen.getByLabelText("Price per capsule (₹)"), "4.20");
    await user.selectOptions(screen.getByLabelText("Give instead"), "m2");
    const cetirizine = screen.getByRole("radiogroup", { name: "Cetirizine availability" });
    await user.click(cetirizine.querySelectorAll("input")[1]!);
    await user.type(screen.getByLabelText("How many do you have?"), "3");
    await user.type(screen.getByLabelText("Price per tablet (₹)"), "2");
    await user.click(screen.getByRole("button", { name: "Send answer" }));

    const post = calls.find((c) => c.method === "POST");
    expect(post?.url).toBe("/api/v1/stores/me/requests/r1/answer");
    expect(post?.body).toEqual({
      note: "",
      lines: [
        { medicineId: "m1", availability: "YES", quantity: null, unitPrice: 4.2, substituteMedicineId: "m2" },
        { medicineId: "m3", availability: "PARTIAL", quantity: 3, unitPrice: 2, substituteMedicineId: null },
      ],
    });
  });
});
