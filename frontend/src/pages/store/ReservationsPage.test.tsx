import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { StoreReservation } from "../../api/types";
import { json, mockFetch } from "../../test/fetchMock";
import { renderPage } from "../../test/renderPage";
import { ReservationsPage } from "./ReservationsPage";

const HELD: StoreReservation = {
  id: "r1",
  requestId: "q1",
  status: "HELD",
  patientName: "Lalitha Nair",
  total: 48,
  complete: true,
  createdAt: "2030-01-01T09:00:00Z",
  expiresAt: "2030-01-01T12:00:00Z",
  collectedAt: null,
  codeLocked: false,
  lines: [{ name: "Glycomet", strength: "500mg", prescribedAs: "Metformin", quantity: 30, asked: 30, unitPrice: 1.6 }],
};

describe("ReservationsPage", () => {
  it("hands over only against the patient's six-digit code, and shows the server's refusal for a wrong one", async () => {
    let collected = false;
    const calls = mockFetch(({ url, body }) => {
      if (url.endsWith("/collect")) {
        if ((body as { code: string }).code === "000000") {
          return json(422, { code: "WRONG_PICKUP_CODE", detail: "That code is not right. 4 attempts left." });
        }
        collected = true;
        return json(204, null);
      }
      return json(200, collected ? [] : [HELD]);
    });
    renderPage(<ReservationsPage />);

    expect(await screen.findByRole("heading", { name: "Lalitha Nair" })).toBeInTheDocument();
    expect(screen.getByText("in place of Metformin")).toBeInTheDocument();
    const input = screen.getByLabelText("Patient's code");
    const handOver = screen.getByRole("button", { name: "Hand over" });

    await userEvent.type(input, "12ab34");
    expect(input).toHaveValue("1234");
    expect(handOver).toBeDisabled();

    await userEvent.clear(input);
    await userEvent.type(input, "000000");
    await userEvent.click(handOver);
    expect(await screen.findByRole("alert")).toHaveTextContent("4 attempts left");
    expect(input).toHaveValue("");

    await userEvent.type(input, "482913");
    await userEvent.click(handOver);
    expect(await screen.findByText("Nothing to keep aside")).toBeInTheDocument();
    expect(calls.filter((c) => c.method === "POST").map((c) => c.body)).toEqual([{ code: "000000" }, { code: "482913" }]);
  });

  it("offers no code box once too many wrong codes have been tried", async () => {
    mockFetch(() => json(200, [{ ...HELD, codeLocked: true }]));
    renderPage(<ReservationsPage />);

    expect(await screen.findByText(/Too many wrong codes/)).toBeInTheDocument();
    expect(screen.queryByLabelText("Patient's code")).not.toBeInTheDocument();
  });

  it("lists finished pick-ups on the other tab", async () => {
    const calls = mockFetch(({ url }) => json(200, url.includes("show=done") ? [{ ...HELD, status: "EXPIRED" }] : []));
    renderPage(<ReservationsPage />);
    await screen.findByText("Nothing to keep aside");

    await userEvent.click(screen.getByRole("tab", { name: "Finished" }));

    expect(await screen.findByText("Not collected in time")).toBeInTheDocument();
    await waitFor(() => expect(calls.at(-1)?.url).toBe("/api/v1/stores/me/reservations?show=done"));
  });
});
