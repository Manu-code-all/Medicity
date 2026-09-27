import { useEffect, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { ApiError } from "../../api/client";
import { workspace } from "../../api/endpoints";
import type { ScanDraft } from "../../api/types";
import type { StartingItem } from "./PrescriptionForm";

interface Props {
  visitId: string;
  /** Called with the photo's id and the draft to check, or no draft when it could not be read. */
  onDraft: (scanId: string, starting: { diagnosis: string; items: StartingItem[] }, note: string | null) => void;
}

/**
 * "Photograph your handwritten slip." Uploads the photo, asks for a draft
 * read from it, and hands the draft to the prescription form to check. On a
 * phone the file input opens the camera.
 */
export function PhotoStart({ visitId, onDraft }: Props) {
  const [preview, setPreview] = useState<string | null>(null);

  useEffect(() => () => {
    if (preview) URL.revokeObjectURL(preview);
  }, [preview]);

  const read = useMutation({
    mutationFn: async (photo: File) => {
      const { scanId } = await workspace.uploadScan(visitId, photo);
      return workspace.readScan(scanId);
    },
    onSuccess: (draft) => onDraft(draft.scanId, toStarting(draft), draft.problem),
  });

  return (
    <div className="photo-start">
      <label className="button button--ghost photo-start__pick">
        {read.isPending ? "Reading your handwriting…" : "Start from a photo of your handwritten slip"}
        <input
          type="file"
          accept="image/jpeg,image/png,image/webp"
          capture="environment"
          className="sr-only"
          disabled={read.isPending}
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (!file) return;
            setPreview(URL.createObjectURL(file));
            read.mutate(file);
          }}
        />
      </label>
      {preview && <img className="photo__img photo__img--small" src={preview} alt="Your handwritten slip" />}
      {read.isError && (
        <p className="error" role="alert">
          {read.error instanceof ApiError ? read.error.message : "Could not upload the photo."}
        </p>
      )}
    </div>
  );
}

function toStarting(draft: ScanDraft): { diagnosis: string; items: StartingItem[] } {
  return {
    diagnosis: draft.diagnosis ?? "",
    items: draft.lines.map(({ read, match }) => ({
      medicineId: match?.medicineId ?? "",
      dosage: read.dosage ?? "",
      frequency: read.frequency ?? "",
      durationDays: read.durationDays ?? 5,
      quantity: read.quantity ?? 1,
      substitutionAllowed: false,
      readAs: read.writtenAs ?? [read.medicine, read.strength].filter(Boolean).join(" "),
    })),
  };
}
