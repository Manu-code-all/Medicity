import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { json, mockFetch } from "../test/fetchMock";
import { DoctorsPage } from "./DoctorsPage";

const EMPTY = { content: [], totalElements: 0, totalPages: 0, number: 0 };
const MENON = {
  content: [{ id: "d4", fullName: "Dr. Kavitha Menon", specialization: "General Medicine", consultationFee: 700, yearsExperience: 12, bio: null, nextSlots: [] }],
  totalElements: 1,
  totalPages: 1,
  number: 0,
};

const INSURERS = [
  { name: "Star Health", kind: "PRIVATE" },
  { name: "CGHS", kind: "GOVERNMENT" },
];

/** The directory's fake server: the insurers list, then whatever the test answers. */
const directory = (respond: Parameters<typeof mockFetch>[0]) =>
  mockFetch((call) => (call.url === "/api/v1/doctors/insurers" ? json(200, INSURERS) : respond(call)));

function renderAt(path: string) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <DoctorsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("DoctorsPage", () => {
  it("filters by insurance, and shows accepted insurers and the price list on each card", async () => {
    const withOffers = {
      ...MENON,
      content: [{
        ...MENON.content[0],
        insurers: ["CGHS", "New India Assurance", "Star Health", "Ayushman Bharat (PM-JAY)"],
        prices: [
          { procedure: "Registration", priceInr: 100, everyVisit: true },
          { procedure: "ECG", priceInr: 300, everyVisit: false },
        ],
      }],
    };
    const calls = directory(({ url }) => json(200, url.endsWith("/specialties") ? [] : withOffers));
    renderAt("/doctors");

    await screen.findByRole("option", { name: "CGHS" });
    await userEvent.selectOptions(screen.getByLabelText("Insurance"), "CGHS");
    expect(calls.some((c) => c.url === "/api/v1/doctors?insurance=CGHS")).toBe(true);

    expect(screen.getByLabelText(/^Accepts CGHS, New India Assurance/)).toHaveTextContent("+1 more");
    await userEvent.click(screen.getByText("Price list"));
    const row = (name: string) => screen.getByRole("row", { name: new RegExp(`^${name}`) });
    expect(row("Consultation")).toHaveTextContent("₹700");
    expect(row("Each visit")).toHaveTextContent("₹800");
    expect(row("ECG")).toHaveTextContent("₹300");
  });

  it("shows each doctor's next free times as links that open booking with that time chosen", async () => {
    const soon = new Date();
    soon.setDate(soon.getDate() + 1);
    soon.setHours(10, 0, 0, 0);
    const later = new Date(soon.getTime() + 30 * 60_000);
    directory(({ url }) => {
      if (url.endsWith("/specialties")) return json(200, []);
      return json(200, {
        ...MENON,
        content: [{
          ...MENON.content[0],
          nextSlots: [
            { id: "s1", startsAt: soon.toISOString(), endsAt: later.toISOString() },
            { id: "s2", startsAt: later.toISOString(), endsAt: later.toISOString() },
          ],
        }],
      });
    });
    renderAt("/doctors");

    const times = await screen.findByRole("list", { name: "Next free times with Dr. Kavitha Menon" });
    const first = within(times).getAllByRole("link")[0]!;
    expect(first).toHaveTextContent(/^Tomorrow, /);
    expect(first).toHaveAttribute("href", "/doctors/d4/book?slot=s1");
    expect(within(times).getByRole("link", { name: "More times" })).toHaveAttribute("href", "/doctors/d4/book");
  });

  it("shows the rating from visits, linked to the reviews", async () => {
    directory(({ url }) =>
      json(200, url.endsWith("/specialties") ? [] : { ...MENON, content: [{ ...MENON.content[0], rating: 4.5, reviewCount: 2 }] }),
    );
    renderAt("/doctors");

    expect(await screen.findByText("4.5", { exact: false })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "2 reviews from visits" })).toHaveAttribute("href", "/doctors/d4/book#reviews");
  });

  it("says so when a doctor has no free times soon", async () => {
    directory(({ url }) => json(200, url.endsWith("/specialties") ? [] : MENON));
    renderAt("/doctors");

    expect(await screen.findByText(/No free times in the next two weeks/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Notify me when a time opens" })).toHaveAttribute("href", "/doctors/d4/book#waitlist");
  });

  it("takes the body guide's answer from the link, and offers the alternative when nobody matches", async () => {
    const calls = directory(({ url }) => {
      if (url.endsWith("/specialties")) return json(200, [{ name: "General Medicine", doctors: 1 }]);
      return json(200, url.includes("General+Medicine") || url.includes("General%20Medicine") ? MENON : EMPTY);
    });
    renderAt("/doctors?specialty=Gastroenterology&zone=abdomen_upper");

    expect(await screen.findByRole("status")).toHaveTextContent("Upper abdomen and stomach");
    expect(screen.getByRole("status")).toHaveTextContent("Showing gastroenterologists");
    expect(calls.some((c) => c.url === "/api/v1/doctors?specialization=Gastroenterology")).toBe(true);

    await userEvent.click(await screen.findByRole("button", { name: "See a general physician instead" }));

    expect(await screen.findByRole("heading", { name: "Dr. Kavitha Menon" })).toBeInTheDocument();
    expect(screen.getByLabelText("Specialization")).toHaveValue("General Medicine");
  });

  it("lists only specialisations someone practises, with how many doctors", async () => {
    directory(({ url }) =>
      url.endsWith("/specialties") ? json(200, [{ name: "Cardiology", doctors: 2 }]) : json(200, EMPTY),
    );
    renderAt("/doctors?q=rao");

    expect(await screen.findByRole("option", { name: "Cardiology (2)" })).toBeInTheDocument();
    expect(screen.getByPlaceholderText("Search by name or speciality")).toHaveValue("rao");
  });
});
