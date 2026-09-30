import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { json, mockFetch } from "../../test/fetchMock";
import { renderPage } from "../../test/renderPage";
import { PracticePage } from "./PracticePage";

const SAVED = {
  consultationFee: 900,
  clinic: null,
  bio: "Heart rhythm clinic.",
  yearsExperience: 10,
  insurers: ["Star Health"],
  prices: [{ procedure: "ECG", priceInr: 300, everyVisit: false }],
  availableInsurers: [
    { name: "Star Health", kind: "PRIVATE" },
    { name: "Care Health", kind: "PRIVATE" },
    { name: "CGHS", kind: "GOVERNMENT" },
  ],
};

describe("PracticePage", () => {
  it("shows what is saved and sends the whole practice back, dropping unnamed charges", async () => {
    const calls = mockFetch(({ method, body }) =>
      method === "PUT" ? json(200, { ...SAVED, ...(body as object) }) : json(200, SAVED),
    );
    renderPage(<PracticePage />);

    const fee = await screen.findByLabelText("Consultation fee (₹)");
    expect(fee).toHaveValue(900);
    expect(screen.getByRole("checkbox", { name: "Star Health" })).toBeChecked();
    expect(screen.getByRole("group", { name: "Government schemes" })).toBeInTheDocument();

    await userEvent.clear(fee);
    await userEvent.type(fee, "1100");
    await userEvent.click(screen.getByRole("checkbox", { name: "CGHS" }));
    await userEvent.type(screen.getByLabelText("Clinic name"), "Rao Heart Clinic");
    await userEvent.type(screen.getByLabelText("Address"), "12, 100 Feet Road");
    Object.defineProperty(globalThis.navigator, "geolocation", {
      configurable: true,
      value: { getCurrentPosition: (ok: PositionCallback) => ok({ coords: { latitude: 12.9784, longitude: 77.6408 } } as GeolocationPosition) },
    });
    await userEvent.click(screen.getByRole("button", { name: "Use my current location" }));
    await userEvent.click(screen.getByRole("button", { name: "+ Add a charge" }));
    await userEvent.type(screen.getByLabelText("Charge 2 name"), "Registration");
    await userEvent.type(screen.getByLabelText("Charge 2 price"), "100");
    await userEvent.click(screen.getByLabelText("Charge 2 every visit"));
    await userEvent.click(screen.getByRole("button", { name: "+ Add a charge" }));   // left empty
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByText(/directory shows the new details/)).toBeInTheDocument();
    expect(calls.find((c) => c.method === "PUT")?.body).toEqual({
      consultationFee: 1100,
      clinic: { name: "Rao Heart Clinic", address: "12, 100 Feet Road", latitude: 12.9784, longitude: 77.6408 },
      yearsExperience: 10,
      bio: "Heart rhythm clinic.",
      insurers: ["Star Health", "CGHS"],
      prices: [
        { procedure: "ECG", priceInr: 300, everyVisit: false },
        { procedure: "Registration", priceInr: 100, everyVisit: true },
      ],
    });
  });

  it("shows the server's reason when a change is refused", async () => {
    mockFetch(({ method }) =>
      method === "PUT"
        ? json(422, { code: "DUPLICATE_PROCEDURE", detail: "\"ECG\" is listed twice" })
        : json(200, SAVED),
    );
    renderPage(<PracticePage />);

    await userEvent.click(await screen.findByRole("button", { name: "Save" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("\"ECG\" is listed twice");
  });
});
