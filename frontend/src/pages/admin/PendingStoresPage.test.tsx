import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { describe, expect, it } from "vitest";
import type { Role, StoreProfile } from "../../api/types";
import { AuthContext, type AuthContextValue } from "../../auth/context";
import { json, mockFetch } from "../../test/fetchMock";
import { renderPage } from "../../test/renderPage";
import { PendingStoresPage } from "./PendingStoresPage";

const STORE: StoreProfile = {
  id: "s9", name: "New Life Pharmacy", licenceNumber: "KA-BLR-20B-99999", phone: "+919876500399",
  addressLine: "4, CMH Road", city: "Bengaluru", latitude: 12.97, longitude: 77.64, holdHours: 3,
  verified: false, verifiedAt: null, ownerName: "Ravi Kumar", ownerEmail: "ravi@example.com",
  registeredAt: "2030-01-01T09:00:00Z", opensAt: "09:00:00", closesAt: "21:00:00", open24h: false, openNow: true,
};

function signedInAs(role: Role, page: ReactNode) {
  const value = { session: { userId: "u1", fullName: "Someone", role } } as AuthContextValue;
  return <AuthContext.Provider value={value}>{page}</AuthContext.Provider>;
}

describe("PendingStoresPage", () => {
  it("shows the licence to check, and verifying takes the store off the list", async () => {
    let pending = [STORE];
    const calls = mockFetch(({ method }) => {
      if (method === "POST") {
        pending = [];
        return json(200, { ...STORE, verified: true });
      }
      return json(200, pending);
    });
    renderPage(signedInAs("ADMIN", <PendingStoresPage />));

    expect(await screen.findByText("KA-BLR-20B-99999")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /verify store/ }));

    expect(await screen.findByText("Nothing waiting")).toBeInTheDocument();
    expect(calls.find((c) => c.method === "POST")?.url).toBe("/api/v1/admin/stores/s9/verify");
  });

  it("asks the server nothing for anyone but an administrator", () => {
    const calls = mockFetch(() => json(200, [STORE]));
    renderPage(signedInAs("CHEMIST", <PendingStoresPage />));

    expect(screen.getByRole("heading", { name: "For administrators" })).toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });
});
