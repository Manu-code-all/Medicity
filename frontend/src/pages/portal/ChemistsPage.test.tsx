import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { json, mockFetch } from "../../test/fetchMock";
import { ChemistsPage } from "./ChemistsPage";

const SAI = {
  id: "s1",
  name: "Sri Sai Medicals",
  phone: "+919876500301",
  addressLine: "12, 100 Feet Road",
  city: "Bengaluru",
  latitude: 12.9745,
  longitude: 77.6408,
  distanceM: 293,
  opensAt: "08:00:00",
  closesAt: "22:00:00",
  open24h: false,
  openNow: true,
  holdHours: 3,
};

function renderPage() {
  const calls = mockFetch(({ url }) => json(200, url.includes("radiusM=1000") ? [] : [SAI]));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <ChemistsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return calls;
}

describe("ChemistsPage", () => {
  it("searches the demo area until the visitor shares a location, and lists stores with distance and hours", async () => {
    const calls = renderPage();

    expect(await screen.findByRole("heading", { name: "Sri Sai Medicals" })).toBeInTheDocument();
    expect(screen.getByText("290 m")).toBeInTheDocument();
    expect(screen.getByText("Open now")).toBeInTheDocument();
    expect(screen.getByText("08:00–22:00")).toBeInTheDocument();
    expect(screen.getByText(/Indiranagar, Bengaluru/)).toBeInTheDocument();
    expect(calls[0]?.url).toBe("/api/v1/stores/nearby?lat=12.9719&lng=77.6412&radiusM=3000");
  });

  it("asks again when the radius changes, and says when nothing is in reach", async () => {
    const calls = renderPage();
    await screen.findByRole("heading", { name: "Sri Sai Medicals" });

    await userEvent.selectOptions(screen.getByRole("combobox"), "1000");

    expect(await screen.findByText("No chemists within 1 km")).toBeInTheDocument();
    expect(calls.at(-1)?.url).toContain("radiusM=1000");
  });
});
