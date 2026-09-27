import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import { AuthContext, type AuthContextValue } from "../auth/context";
import { RegisterDoctorPage } from "./RegisterDoctorPage";

function renderPage(registerDoctor: AuthContextValue["registerDoctor"]) {
  const value = { session: null, registerDoctor } as unknown as AuthContextValue;
  render(
    <AuthContext.Provider value={value}>
      <MemoryRouter initialEntries={["/register/doctor"]}>
        <Routes>
          <Route path="/register/doctor" element={<RegisterDoctorPage />} />
          <Route path="/doctor/hours" element={<p>Hours page</p>} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

async function fill() {
  await userEvent.type(screen.getByLabelText("Full name"), "Nisha Rao");
  await userEvent.type(screen.getByLabelText("Email"), "nisha@example.com");
  await userEvent.type(screen.getByLabelText(/Mobile number/), "9876512345");
  await userEvent.type(screen.getByLabelText("Password"), "a-long-enough-password");
  await userEvent.selectOptions(screen.getByLabelText("Speciality"), "Dermatology");
  await userEvent.type(screen.getByLabelText("Registration number"), "KMC-12345");
  await userEvent.type(screen.getByLabelText("Qualifications"), "MBBS, MD");
  await userEvent.type(screen.getByLabelText("Years of practice"), "6");
  await userEvent.type(screen.getByLabelText("Consultation fee (₹)"), "800");
}

describe("RegisterDoctorPage", () => {
  it("registers with the council and number, then goes to set hours", async () => {
    const registerDoctor = vi.fn(async () => {});
    renderPage(registerDoctor);

    await fill();
    await userEvent.selectOptions(screen.getByLabelText("Medical council you are registered with"), "__other");
    await userEvent.type(screen.getByLabelText("Council name"), "Goa Medical Council");
    await userEvent.click(screen.getByRole("button", { name: "Register" }));

    expect(await screen.findByText("Hours page")).toBeInTheDocument();
    expect(registerDoctor).toHaveBeenCalledWith(
      expect.objectContaining({
        specialization: "Dermatology",
        medicalCouncil: "Goa Medical Council",
        registrationNumber: "KMC-12345",
        yearsExperience: 6,
        consultationFee: 800,
      }),
    );
  });

  it("says when the registration number is already on Medicity", async () => {
    renderPage(
      vi.fn().mockRejectedValue(
        new ApiError(409, { code: "REGISTRATION_TAKEN", detail: "A doctor with this registration number is already on Medicity" }),
      ),
    );

    await fill();
    await userEvent.click(screen.getByRole("button", { name: "Register" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("already on Medicity");
  });
});
