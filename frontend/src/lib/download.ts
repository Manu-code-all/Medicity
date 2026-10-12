/** Hands the browser a JSON document to save as a file. */
export function saveJson(filename: string, data: unknown): void {
  saveBlob(filename, new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }));
}

/** Hands the browser a file (a CSV, say) to save. */
export function saveBlob(filename: string, blob: Blob): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
