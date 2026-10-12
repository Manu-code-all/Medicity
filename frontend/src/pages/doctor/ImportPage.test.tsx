import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ImportReport } from "../../api/types";
import { json, mockFetch, type RecordedCall } from "../../test/fetchMock";
import { renderPage } from "../../test/renderPage";
import { ImportPage } from "./ImportPage";

const SHA = "a".repeat(64);

const PREVIEW: ImportReport = {
  applied: false,
  fileSha256: SHA,
  totalRows: 12,
  validRows: 9,
  rejectedRows: 3,
  created: 6,
  updated: 2,
  unchanged: 1,
  errorCount: 3,
  errors: [
    { row: 3, column: "Date of birth", code: "DOB_INVALID", message: "Use DD/MM/YYYY or YYYY-MM-DD, a real date in the past (not before 1900)." },
    { row: 5, column: "Phone", code: "PHONE_INVALID", message: "A phone number has 10 to 15 digits (spaces, dashes and a leading + are fine)." },
    { row: 8, column: null, code: "AMBIGUOUS_MATCH", message: "The email is one saved patient and the phone another." },
  ],
  errorsTruncated: false,
  ignoredColumns: ["Ward"],
};

const csv = (name = "patients.csv") => new File(["Full name,Phone\nAsha,9876543210\n"], name, { type: "text/csv" });

function server(preview: ImportReport = PREVIEW, onConfirm?: (call: RecordedCall) => Response) {
  return mockFetch((call) => {
    if (call.url.endsWith("/template")) return new Response("﻿Full name,Phone", { headers: { "Content-Type": "text/csv" } });
    if (call.url.endsWith("/dry-run")) return json(200, preview);
    if (call.url.endsWith("/error-report")) return new Response("Row,Column", { headers: { "Content-Type": "text/csv" } });
    if (call.url.endsWith("/confirm"))
      return onConfirm?.(call) ?? json(200, { ...preview, applied: true });
    return json(404, {});
  });
}

beforeEach(() => {
  vi.stubGlobal("URL", Object.assign(URL, { createObjectURL: vi.fn(() => "blob:csv"), revokeObjectURL: vi.fn() }));
});
afterEach(() => vi.restoreAllMocks());

describe("ImportPage", () => {
  it("downloads the template", async () => {
    const calls = server();
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    renderPage(<ImportPage />);

    await userEvent.click(screen.getByRole("button", { name: "Download the template" }));

    await waitFor(() => expect(click).toHaveBeenCalledTimes(1));
    expect(calls[0]?.url).toContain("/api/v1/doctors/me/import/patients/template");
  });

  it("checks the chosen file straight away and shows counts and the first problems, saving nothing", async () => {
    const calls = server();
    renderPage(<ImportPage />);

    await userEvent.upload(screen.getByLabelText("Patient list (CSV)"), csv());

    expect(await screen.findByText("Will be added")).toBeInTheDocument();
    const counts = screen.getByRole("list");
    expect(counts).toHaveTextContent("12");
    expect(counts).toHaveTextContent("6Will be added");
    expect(counts).toHaveTextContent("3Will be skipped");
    expect(screen.getByText(/3 problems found/)).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "Date of birth" })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "Whole row" })).toBeInTheDocument();
    expect(screen.getByText(/Columns not used: Ward/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Import 8 patients and skip 3" })).toBeEnabled();

    expect(calls.map((c) => c.url.split("/").pop())).toEqual(["dry-run"]);
    expect(calls[0]?.method).toBe("POST");
    expect((calls[0]?.form?.get("file") as File).name).toBe("patients.csv");
  });

  it("imports the file that was checked, sending its hash, and shows the result", async () => {
    const calls = server(PREVIEW, () => json(200, { ...PREVIEW, applied: true, created: 6, updated: 2, unchanged: 1 }));
    renderPage(<ImportPage />);
    await userEvent.upload(screen.getByLabelText("Patient list (CSV)"), csv());

    await userEvent.click(await screen.findByRole("button", { name: /^Import 8 patients/ }));

    expect(await screen.findByText(/Done\. 6 added, 2 updated, 1 already up to date, 3 skipped\./)).toBeInTheDocument();
    const confirm = calls.find((c) => c.url.endsWith("/confirm"));
    expect(confirm?.form?.get("fileSha256")).toBe(SHA);
    expect((confirm?.form?.get("file") as File).name).toBe("patients.csv");
    expect(screen.getByRole("button", { name: "Import another file" })).toBeInTheDocument();
  });

  it("downloads every problem as a CSV", async () => {
    const calls = server();
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    renderPage(<ImportPage />);
    await userEvent.upload(screen.getByLabelText("Patient list (CSV)"), csv());

    await userEvent.click(await screen.findByRole("button", { name: "Download all problems (CSV)" }));

    await waitFor(() => expect(click).toHaveBeenCalledTimes(1));
    expect(calls.some((c) => c.url.endsWith("/error-report"))).toBe(true);
  });

  it("says when every row is fine, and offers nothing to import when nothing would change", async () => {
    server({ ...PREVIEW, rejectedRows: 0, errorCount: 0, errors: [], created: 0, updated: 0, unchanged: 12, ignoredColumns: [] });
    renderPage(<ImportPage />);
    await userEvent.upload(screen.getByLabelText("Patient list (CSV)"), csv());

    expect(await screen.findByText("Every row passed the checks.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^Import 0 patients/ })).toBeDisabled();
    expect(screen.getByText("There is nothing to add or change in this file.")).toBeInTheDocument();
  });

  it("shows the server's reason when the file is refused", async () => {
    mockFetch(() => json(422, { code: "NOT_A_CSV", detail: "This is not a CSV text file." }));
    renderPage(<ImportPage />);

    await userEvent.upload(screen.getByLabelText("Patient list (CSV)"), csv("book.xlsx"));

    expect(await screen.findByRole("alert")).toHaveTextContent("This is not a CSV text file.");
    expect(screen.queryByRole("button", { name: /^Import / })).toBeNull();
  });

  it("does not even send a file over 2 MB", async () => {
    const calls = server();
    renderPage(<ImportPage />);
    const big = new File([new Uint8Array(2 * 1024 * 1024 + 1)], "big.csv", { type: "text/csv" });

    await userEvent.upload(screen.getByLabelText("Patient list (CSV)"), big);

    expect(await screen.findByRole("alert")).toHaveTextContent("larger than 2 MB");
    expect(calls).toHaveLength(0);
  });

  it("keeps the preview and says nothing was saved when the import fails", async () => {
    server(PREVIEW, () => json(409, { code: "FILE_CHANGED", detail: "This is not the file that was checked." }));
    renderPage(<ImportPage />);
    await userEvent.upload(screen.getByLabelText("Patient list (CSV)"), csv());

    await userEvent.click(await screen.findByRole("button", { name: /^Import 8 patients/ }));

    expect(await screen.findByRole("alert")).toHaveTextContent("This is not the file that was checked.");
    expect(screen.getByText("Will be added")).toBeInTheDocument();
  });
});
