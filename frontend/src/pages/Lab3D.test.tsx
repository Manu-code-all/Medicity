import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { canDraw3D } from "../three/capability";
import { Lab3D } from "./Lab3D";

function setDevice({ reduced = false, cores = 8, memory = 8, saveData = false }: { reduced?: boolean; cores?: number; memory?: number | undefined; saveData?: boolean } = {}) {
  vi.stubGlobal("matchMedia", (query: string) => ({ matches: reduced && query.includes("reduce"), media: query, addEventListener() {}, removeEventListener() {} }));
  Object.defineProperty(navigator, "hardwareConcurrency", { configurable: true, value: cores });
  Object.defineProperty(navigator, "deviceMemory", { configurable: true, value: memory });
  Object.defineProperty(navigator, "connection", { configurable: true, value: { saveData } });
}

describe("canDraw3D", () => {
  beforeEach(() => {
    // jsdom has no WebGL; give it a canvas that claims to.
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue({} as RenderingContext);
  });
  afterEach(() => vi.restoreAllMocks());

  it("is on for a capable device", () => {
    setDevice();
    expect(canDraw3D()).toBe(true);
  });

  it.each([
    ["reduced motion", { reduced: true }],
    ["Data Saver", { saveData: true }],
    ["fewer than four cores", { cores: 2 }],
    ["under 4 GB of memory", { memory: 2 }],
  ])("is off for %s", (_name, device) => {
    setDevice(device);
    expect(canDraw3D()).toBe(false);
  });

  it("is off without WebGL", () => {
    setDevice();
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(null);
    expect(canDraw3D()).toBe(false);
  });
});

describe("Lab3D without 3D", () => {
  beforeEach(() => {
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(null);
  });
  afterEach(() => vi.restoreAllMocks());

  it("is complete and usable as flat pages: the heading, the body guide's areas, the map's words", async () => {
    // jsdom has no WebGL, so this is the fallback every weak device gets.
    setDevice();
    render(
      <MemoryRouter>
        <Lab3D />
      </MemoryRouter>,
    );

    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("From prescription to medicines in hand, near you.");
    for (const chapter of ["Book", "Visit", "Prescription", "Chemists nearby", "Pick up"]) {
      expect(screen.getByRole("heading", { level: 2, name: chapter })).toBeInTheDocument();
    }
    // The flat body is the real one, with a button per area.
    await userEvent.click(screen.getByRole("button", { name: "Chest" }));
    expect(screen.getByText(/You chose chest/i)).toBeInTheDocument();
    expect(screen.getByText(/Three have answered/)).toBeInTheDocument();
    expect(screen.getByText(/The 3D map is off on this device/)).toBeInTheDocument();
    expect(document.querySelector('meta[name="robots"]')).toHaveAttribute("content", "noindex");
  });
});
