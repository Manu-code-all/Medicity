import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { ScanDraft } from "../../api/types";
import { json, mockFetch } from "../../test/fetchMock";
import { PhotoStart } from "./PhotoStart";

const DRAFT: ScanDraft = {
  scanId: "scan-1",
  status: "DRAFTED",
  problem: null,
  diagnosis: "GERD",
  lines: [
    {
      read: {
        writtenAs: "Omez 20 OD AC x14", medicine: "Omez", strength: "20mg", dosage: "1 capsule",
        frequency: "Once daily before food", durationDays: 14, quantity: 14, confidence: 0.9,
      },
      match: { medicineId: "m-omez", name: "Omez", strength: "20mg" },
    },
    {
      read: {
        writtenAs: "Pan-D 40 BD", medicine: "Pan-D", strength: "40mg", dosage: null,
        frequency: "Twice daily", durationDays: null, quantity: null, confidence: 0.5,
      },
      match: null,
    },
  ],
  unreadable: [],
};

describe("PhotoStart", () => {
  it("uploads the photo, asks for a draft, and hands over lines to check; unmatched ones stay empty", async () => {
    // jsdom has no object URLs.
    vi.stubGlobal("URL", Object.assign(URL, { createObjectURL: () => "blob:photo", revokeObjectURL: () => {} }));
    const calls = mockFetch(({ url }) =>
      url.endsWith("/scans") ? json(201, { scanId: "scan-1" }) : json(200, DRAFT),
    );
    const onDraft = vi.fn();
    render(
      <QueryClientProvider client={new QueryClient()}>
        <PhotoStart visitId="v1" onDraft={onDraft} />
      </QueryClientProvider>,
    );

    const input = screen.getByLabelText(/Start from a photo/);
    await userEvent.upload(input, new File(["png"], "slip.png", { type: "image/png" }));

    await waitFor(() => expect(onDraft).toHaveBeenCalled());
    expect(calls.map((c) => `${c.method} ${c.url}`)).toEqual([
      "POST /api/v1/doctors/me/visits/v1/scans",
      "POST /api/v1/doctors/me/scans/scan-1/read",
    ]);
    const [scanId, starting] = onDraft.mock.calls[0]!;
    expect(scanId).toBe("scan-1");
    expect(starting.diagnosis).toBe("GERD");
    expect(starting.items[0]).toMatchObject({ medicineId: "m-omez", durationDays: 14, readAs: "Omez 20 OD AC x14" });
    // Not matched to the catalogue: the doctor must choose.
    expect(starting.items[1]).toMatchObject({ medicineId: "", readAs: "Pan-D 40 BD" });
  });
});
