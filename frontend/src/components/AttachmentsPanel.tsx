import { useRef, useState } from "react";
import { Paperclip } from "@phosphor-icons/react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError, requestImageUrl } from "../api/client";
import { attachments } from "../api/endpoints";
import type { Attachment } from "../api/types";

const sizeLabel = (bytes: number) => (bytes < 1024 * 1024 ? `${Math.ceil(bytes / 1024)} KB` : `${(bytes / 1048576).toFixed(1)} MB`);

/**
 * A visit's attached records. The patient adds and removes them before the
 * visit; the doctor reads them. Files are fetched with the sign-in token and
 * shown from a blob, never from a public address.
 */
export function AttachmentsPanel({ appointmentId, canEdit, hideWhenEmpty = false }: {
  appointmentId: string;
  canEdit: boolean;
  hideWhenEmpty?: boolean;
}) {
  const queryClient = useQueryClient();
  const key = ["attachments", appointmentId];
  const input = useRef<HTMLInputElement>(null);
  const [note, setNote] = useState("");
  const [viewing, setViewing] = useState<{ id: string; url: string } | null>(null);
  const [openError, setOpenError] = useState(false);

  const list = useQuery({ queryKey: key, queryFn: () => attachments.list(appointmentId) });
  const upload = useMutation({
    mutationFn: (file: File) => attachments.upload(appointmentId, file, note),
    onSuccess: () => {
      setNote("");
      if (input.current) input.current.value = "";
      void queryClient.invalidateQueries({ queryKey: key });
    },
  });
  const remove = useMutation({
    mutationFn: (id: string) => attachments.remove(appointmentId, id),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: key }),
  });

  async function open(a: Attachment) {
    setOpenError(false);
    try {
      const url = await requestImageUrl(attachments.path(appointmentId, a.id));
      if (a.contentType === "application/pdf") {
        window.open(url, "_blank", "noopener");
        window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
      } else {
        if (viewing) URL.revokeObjectURL(viewing.url);
        setViewing({ id: a.id, url });
      }
    } catch {
      setOpenError(true);
    }
  }

  const files = list.data ?? [];
  if (hideWhenEmpty && files.length === 0) return null;

  return (
    <div className="attachments">
      {list.isError && <p className="error">Could not load the records.</p>}
      {files.length > 0 && (
        <ul className="attachments__list" aria-label="Attached records">
          {files.map((a) => (
            <li key={a.id}>
              <span aria-hidden="true">{a.contentType === "application/pdf" ? "📄" : "🖼️"}</span>
              <button type="button" className="link" onClick={() => void open(a)}>
                {a.fileName}
              </button>
              <span className="muted small">
                {sizeLabel(a.sizeBytes)}
                {a.note && ` · ${a.note}`}
              </span>
              {canEdit && (
                <button type="button" className="link link--danger" onClick={() => remove.mutate(a.id)}>
                  Remove
                </button>
              )}
              {viewing?.id === a.id && <img className="attachments__preview" src={viewing.url} alt={a.fileName} />}
            </li>
          ))}
        </ul>
      )}
      {openError && <p className="error">Could not open the file.</p>}
      {canEdit && files.length < 5 && (
        <div className="attachments__add">
          <label htmlFor={`attach-${appointmentId}`}>
            <Paperclip size={16} aria-hidden="true" /> Add a report or earlier prescription (PDF or photo, up to 5 MB)
          </label>
          <input
            id={`attach-${appointmentId}`}
            ref={input}
            type="file"
            accept="application/pdf,image/jpeg,image/png,image/webp"
            disabled={upload.isPending}
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) upload.mutate(file);
            }}
          />
          <input
            aria-label="A note for the doctor (optional)"
            placeholder="A note for the doctor (optional), e.g. blood test from May"
            maxLength={255}
            value={note}
            onChange={(e) => setNote(e.target.value)}
          />
          {upload.isPending && <p className="muted small">Uploading…</p>}
          {upload.isError && (
            <p className="error" role="alert">
              {upload.error instanceof ApiError ? upload.error.message : "Could not attach the file."}
            </p>
          )}
        </div>
      )}
    </div>
  );
}
