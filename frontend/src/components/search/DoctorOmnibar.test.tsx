import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { json, mockFetch } from "../../test/fetchMock";
import { DoctorOmnibar } from "./DoctorOmnibar";

function Where() {
  const location = useLocation();
  return <p>At {location.pathname + location.search}</p>;
}

function renderBox() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={["/"]}>
        <Routes>
          <Route path="/" element={<DoctorOmnibar />} />
          <Route path="/doctors" element={<Where />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const SUGGESTIONS = {
  specialties: [{ name: "Gastroenterology", doctors: 1 }],
  doctors: [
    { id: "d5", fullName: "Dr. Arvind Kulkarni", specialization: "Gastroenterology", consultationFee: 1300, yearsExperience: 16, bio: null },
  ],
};

describe("DoctorOmnibar", () => {
  it("asks once typing pauses, groups specialities and doctors, and opens the one chosen with the keyboard", async () => {
    const calls = mockFetch(() => json(200, SUGGESTIONS));
    renderBox();

    await userEvent.type(screen.getByRole("combobox"), "gast");

    expect(await screen.findByRole("option", { name: /^Gastroenterology/ })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: /Dr. Arvind Kulkarni/ })).toBeInTheDocument();
    // One request for the whole word, not one per letter.
    expect(calls.map((c) => c.url)).toEqual(["/api/v1/doctors/suggest?q=gast"]);

    await userEvent.keyboard("{ArrowDown}{ArrowDown}");
    expect(screen.getByRole("option", { name: /Dr. Arvind Kulkarni/ })).toHaveAttribute("aria-selected", "true");
    await userEvent.keyboard("{Enter}");

    expect(await screen.findByText("At /doctors?q=Dr.+Arvind+Kulkarni")).toBeInTheDocument();
  });

  it("Enter with nothing highlighted searches for exactly what was typed", async () => {
    mockFetch(() => json(200, { specialties: [], doctors: [] }));
    renderBox();

    await userEvent.type(screen.getByRole("combobox"), "knee pain{Enter}");

    expect(await screen.findByText("At /doctors?q=knee+pain")).toBeInTheDocument();
  });
});
