import { useRef, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { ApiError } from "../../api/client";
import { clinicImport } from "../../api/endpoints";
import type { ImportReport } from "../../api/types";
import { saveBlob } from "../../lib/download";

const MAX_BYTES = 2 * 1024 * 1024;
/** How many problems are shown on the page; the downloadable report has every one. */
const SHOWN = 10;

/**
 * Bringing an old patient list in from Excel or CSV: download the template,
 * choose the file, see what would happen, then confirm. Nothing is saved until
 * the confirm, and the file confirmed is the file that was checked.
 */
export function ImportPage() {
  const input = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [tooBig, setTooBig] = useState(false);

  const template = useMutation({
    mutationFn: clinicImport.template,
    onSuccess: (blob) => saveBlob("medicity-patient-import-template.csv", blob),
  });
  const check = useMutation({ mutationFn: (f: File) => clinicImport.dryRun(f) });
  const run = useMutation({ mutationFn: (v: { file: File; sha: string }) => clinicImport.confirm(v.file, v.sha) });
  const errors = useMutation({
    mutationFn: (f: File) => clinicImport.errorReport(f),
    onSuccess: (blob) => saveBlob("medicity-patient-import-errors.csv", blob),
  });

  function reset() {
    setFile(null);
    setTooBig(false);
    check.reset();
    run.reset();
    errors.reset();
    if (input.current) input.current.value = "";
  }

  function choose(picked: File | undefined) {
    check.reset();
    run.reset();
    errors.reset();
    setTooBig(false);
    if (!picked) {
      setFile(null);
      return;
    }
    if (picked.size > MAX_BYTES) {
      setFile(null);
      setTooBig(true);
      return;
    }
    setFile(picked);
    check.mutate(picked);
  }

  const done = run.data;
  const preview = check.data;

  return (
    <div className="stack">
      <header>
        <h1 className="portal__title">Import patients</h1>
        <p className="muted">
          Move your old records in from Excel or a CSV file. Nothing is saved until you have seen what will happen and
          confirmed.
        </p>
      </header>

      <section className="card stack" aria-labelledby="import-how">
        <h2 id="import-how" className="portal__subtitle">
          1. Get the template
        </h2>
        <p className="muted small">
          Columns: full name, phone, email, date of birth, gender, blood group, allergies, notes. Only the name and one
          of phone or email are required. Dates are day first (DD/MM/YYYY) or YYYY-MM-DD. Up to 5,000 rows and 2 MB per
          file. In Excel choose Save As, then CSV.
        </p>
        <div className="import-actions">
          <button type="button" className="button--ghost" disabled={template.isPending} onClick={() => template.mutate()}>
            {template.isPending ? "Preparing…" : "Download the template"}
          </button>
        </div>
        {template.isError && (
          <p className="error" role="alert">
            Could not download the template. Try again in a moment.
          </p>
        )}
        <p className="muted small">
          These people are kept as your own records. They do not get accounts. When a patient later signs up with the
          same email and confirms that address, they are asked whether to link your record, and you are never able to
          show them anything they have not agreed to.
        </p>
      </section>

      <section className="card stack" aria-labelledby="import-file">
        <h2 id="import-file" className="portal__subtitle">
          2. Choose your file
        </h2>
        <div className="import-file">
          <label>
            <span className="sr-only">CSV file</span>
            <input
              ref={input}
              type="file"
              accept=".csv,text/csv,text/plain"
              aria-label="Patient list (CSV)"
              disabled={check.isPending || run.isPending}
              onChange={(e) => choose(e.target.files?.[0])}
            />
          </label>
          {file && (
            <button type="button" className="link" onClick={reset} disabled={run.isPending}>
              Start over
            </button>
          )}
        </div>
        {tooBig && (
          <p className="error" role="alert">
            That file is larger than 2 MB. Split it into smaller files.
          </p>
        )}
        {check.isPending && <p className="muted" role="status">Checking every row…</p>}
        {check.isError && (
          <p className="error" role="alert">
            {check.error instanceof ApiError ? check.error.message : "Could not check the file."}
          </p>
        )}
      </section>

      {preview && file && !done && (
        <section className="card stack" aria-labelledby="import-preview">
          <h2 id="import-preview" className="portal__subtitle">
            3. Check, then import
          </h2>
          <Counts
            items={[
              ["Rows in the file", preview.totalRows],
              ["Will be added", preview.created],
              ["Will be updated", preview.updated],
              ["Already up to date", preview.unchanged],
              ["Will be skipped", preview.rejectedRows],
            ]}
          />
          {preview.ignoredColumns.length > 0 && (
            <p className="muted small">Columns not used: {preview.ignoredColumns.join(", ")}.</p>
          )}
          <Problems report={preview} />
          {preview.errorCount > 0 && (
            <div className="import-actions">
              <button type="button" className="button--ghost" disabled={errors.isPending} onClick={() => errors.mutate(file)}>
                {errors.isPending ? "Preparing…" : "Download all problems (CSV)"}
              </button>
              <span className="muted small">Fix them in your sheet and choose it again, or import the good rows now.</span>
            </div>
          )}
          {errors.isError && (
            <p className="error" role="alert">
              Could not prepare the problem list.
            </p>
          )}
          {run.isError && (
            <p className="error" role="alert">
              {run.error instanceof ApiError ? run.error.message : "The import did not finish. Nothing was saved."}
            </p>
          )}
          <div className="import-actions">
            <button
              type="button"
              disabled={run.isPending || preview.created + preview.updated === 0}
              onClick={() => run.mutate({ file, sha: preview.fileSha256 })}
            >
              {run.isPending ? "Importing…" : importLabel(preview)}
            </button>
            {preview.created + preview.updated === 0 && (
              <span className="muted small">There is nothing to add or change in this file.</span>
            )}
          </div>
        </section>
      )}

      {done && (
        <section className="card stack" aria-labelledby="import-done">
          <h2 id="import-done" className="portal__subtitle">
            Imported
          </h2>
          <p className="notice notice--ok" role="status">
            Done. {done.created} added, {done.updated} updated, {done.unchanged} already up to date
            {done.rejectedRows > 0 ? `, ${done.rejectedRows} skipped` : ""}.
          </p>
          <Counts
            items={[
              ["Added", done.created],
              ["Updated", done.updated],
              ["Already up to date", done.unchanged],
              ["Skipped", done.rejectedRows],
            ]}
          />
          {done.rejectedRows > 0 && file && (
            <div className="import-actions">
              <button type="button" className="button--ghost" disabled={errors.isPending} onClick={() => errors.mutate(file)}>
                Download the skipped rows' problems (CSV)
              </button>
            </div>
          )}
          <div className="import-actions">
            <button type="button" className="button--ghost" onClick={reset}>
              Import another file
            </button>
          </div>
        </section>
      )}
    </div>
  );
}

function importLabel(r: ImportReport) {
  const n = r.created + r.updated;
  return `Import ${n} ${n === 1 ? "patient" : "patients"}${r.rejectedRows > 0 ? ` and skip ${r.rejectedRows}` : ""}`;
}

function Counts({ items }: { items: [string, number][] }) {
  return (
    <ul className="import-counts">
      {items.map(([label, n]) => (
        <li key={label}>
          <strong>{n}</strong>
          <span>{label}</span>
        </li>
      ))}
    </ul>
  );
}

function Problems({ report }: { report: ImportReport }) {
  if (report.errorCount === 0) {
    return (
      <p className="notice notice--ok" role="status">
        Every row passed the checks.
      </p>
    );
  }
  const shown = report.errors.slice(0, SHOWN);
  return (
    <div className="stack">
      <p className="notice" role="status">
        {report.errorCount} {report.errorCount === 1 ? "problem" : "problems"} found. Rows with a problem are skipped;
        the rest can still be imported.
      </p>
      <div className="table-wrap">
        <table className="rx__table">
          <caption className="sr-only">First problems found</caption>
          <thead>
            <tr>
              <th scope="col" className="num">
                Row
              </th>
              <th scope="col">Column</th>
              <th scope="col">Problem</th>
            </tr>
          </thead>
          <tbody>
            {shown.map((e, i) => (
              <tr key={`${e.row}-${e.code}-${i}`}>
                <td className="num">{e.row}</td>
                <td>{e.column ?? "Whole row"}</td>
                <td>{e.message}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {report.errorCount > shown.length && (
        <p className="muted small">Showing the first {shown.length}. Download the list for the rest.</p>
      )}
    </div>
  );
}
