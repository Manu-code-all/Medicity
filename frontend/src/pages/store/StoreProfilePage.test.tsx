import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { StoreProfile } from "../../api/types";
import { json, mockFetch } from "../../test/fetchMock";
import { renderPage } from "../../test/renderPage";
import { StoreProfilePage } from "./StoreProfilePage";

const STORE: StoreProfile = {
  id: "s1", name: "Sri Sai Medicals", licenceNumber: "KA-DL-0001", phone: "+919876500301",
  addressLine: "100 Feet Road", city: "Bengaluru", latitude: 12.97, longitude: 77.64, holdHours: 3,
  verified: true, verifiedAt: "2030-01-01T00:00:00Z", ownerName: "Ravi Kumar", ownerEmail: "chemist@medicity.demo",
  registeredAt: "2029-12-01T00:00:00Z", opensAt: "09:00:00", closesAt: "21:00:00", open24h: false, openNow: true,
};

describe("StoreProfilePage", () => {
  it("shows a verified store with its details", async () => {
    mockFetch(() => json(200, STORE));
    renderPage(<StoreProfilePage />);

    expect(await screen.findByRole("heading", { name: "Sri Sai Medicals" })).toBeInTheDocument();
    expect(screen.getByText(/Verified/)).toBeInTheDocument();
    expect(screen.getByText("100 Feet Road, Bengaluru")).toBeInTheDocument();
    expect(screen.getByText("09:00–21:00 · open now")).toBeInTheDocument();
    expect(screen.getByText("3 hours")).toBeInTheDocument();
    expect(screen.getByText("KA-DL-0001")).toBeInTheDocument();
  });

  it("tells an unverified store that patients cannot find it yet", async () => {
    mockFetch(() => json(200, { ...STORE, verified: false, verifiedAt: null }));
    renderPage(<StoreProfilePage />);
    expect(await screen.findByText("Waiting for verification.")).toBeInTheDocument();
    expect(screen.getByText(/patients cannot find your store/)).toBeInTheDocument();
  });

  it("edits the store, keeps the licence locked and saves", async () => {
    const calls = mockFetch(({ method }) => json(200, method === "PUT" ? { ...STORE, holdHours: 4 } : STORE));
    renderPage(<StoreProfilePage />);

    await userEvent.click(await screen.findByRole("button", { name: "Edit store details" }));
    expect(screen.getByLabelText("Drug licence number")).toBeDisabled();
    await userEvent.selectOptions(screen.getByLabelText("Keep reserved medicines aside for"), "4");
    await userEvent.click(screen.getByRole("button", { name: "Save changes" }));

    expect(await screen.findByText("4 hours")).toBeInTheDocument();
    const put = calls.find((c) => c.method === "PUT")!;
    expect(put.url).toBe("/api/v1/stores/me");
    expect(put.body).toMatchObject({ name: "Sri Sai Medicals", holdHours: 4, opensAt: "09:00" });
  });

  it("shows the server's message when saving fails, and Cancel discards the edit", async () => {
    mockFetch(({ method }) => (method === "PUT" ? json(409, { detail: "Phone already used" }) : json(200, STORE)));
    renderPage(<StoreProfilePage />);

    await userEvent.click(await screen.findByRole("button", { name: "Edit store details" }));
    await userEvent.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Phone already used");

    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.getByRole("button", { name: "Edit store details" })).toBeInTheDocument();
  });

  it("says so when the store cannot load", async () => {
    mockFetch(() => json(500, { detail: "boom" }));
    renderPage(<StoreProfilePage />);
    expect(await screen.findByText("Could not load your store.")).toBeInTheDocument();
  });
});
