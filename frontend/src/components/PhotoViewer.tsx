import { useEffect, useState } from "react";
import { requestImageUrl } from "../api/client";

/**
 * "See the doctor's handwritten original": fetched only when asked for, with
 * the bearer token, and released when closed.
 */
export function PhotoViewer({ path, label = "View the doctor's handwritten original" }: { path: string; label?: string }) {
  const [open, setOpen] = useState(false);
  const [url, setUrl] = useState<string | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    if (!open) return;
    let created: string | null = null;
    let cancelled = false;
    requestImageUrl(path)
      .then((u) => {
        created = u;
        if (cancelled) URL.revokeObjectURL(u);
        else setUrl(u);
      })
      .catch(() => !cancelled && setError(true));
    return () => {
      cancelled = true;
      if (created) URL.revokeObjectURL(created);
      setUrl(null);
    };
  }, [open, path]);

  return (
    <div className="photo">
      <button type="button" className="link" onClick={() => setOpen((o) => !o)} aria-expanded={open}>
        {open ? "Hide the original" : label}
      </button>
      {open && error && <p className="error">Could not load the photo.</p>}
      {open && url && <img className="photo__img" src={url} alt="The doctor's handwritten prescription" />}
    </div>
  );
}
