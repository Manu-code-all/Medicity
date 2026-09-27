import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { ApiError } from "../../api/client";
import { medicineRequests } from "../../api/endpoints";
import type { Prescription } from "../../api/types";
import { LocationBar } from "../../components/LocationBar";
import { usePosition } from "../../lib/geo";

/**
 * "Ask all nearby chemists" for one prescription: where, how far, and which
 * medicines. The medicines come from the prescription, never typed in, so
 * every store receives exactly what the doctor wrote.
 */
export function AskChemists({ prescription }: { prescription: Prescription }) {
  const medicineIds = prescription.items.map((item) => item.medicineId);
  const position = usePosition();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [radiusM, setRadiusM] = useState(3000);
  const [chosen, setChosen] = useState<string[]>(medicineIds);

  const ask = useMutation({
    mutationFn: () =>
      medicineRequests.ask({
        prescriptionId: prescription.id,
        latitude: position.point.lat,
        longitude: position.point.lng,
        radiusM,
        medicineIds: chosen.length === medicineIds.length ? undefined : chosen,
      }),
    onSuccess: (created) => {
      void queryClient.invalidateQueries({ queryKey: ["medicine-requests"] });
      navigate(`/portal/requests/${created.id}`);
    },
  });

  return (
    <div className="ask">
      <LocationBar position={position}>
        <label className="inline-field">
          Within
          <select value={radiusM} onChange={(e) => setRadiusM(Number(e.target.value))}>
            <option value={1000}>1 km</option>
            <option value={3000}>3 km</option>
            <option value={5000}>5 km</option>
          </select>
        </label>
      </LocationBar>

      <fieldset className="ask__medicines">
        <legend>Ask about</legend>
        {prescription.items.map((item) => {
          const id = item.medicineId;
          return (
            <label key={id} className="check">
              <input
                type="checkbox"
                checked={chosen.includes(id)}
                onChange={(e) =>
                  setChosen((prev) => (e.target.checked ? [...prev, id] : prev.filter((x) => x !== id)))
                }
              />
              {item.medicine} {item.strength} × {item.quantity}
              {item.substitutionAllowed && <span className="rx__substitute">Cheaper brand OK</span>}
            </label>
          );
        })}
      </fieldset>

      {ask.isError && (
        <p className="error" role="alert">
          {ask.error instanceof ApiError ? ask.error.message : "Could not send your question."}
        </p>
      )}
      <button type="button" disabled={ask.isPending || chosen.length === 0} onClick={() => ask.mutate()}>
        {ask.isPending ? "Asking…" : "Ask every chemist nearby"}
      </button>
      <p className="muted small">
        Stores see your first name, your doctor and the medicines, not your diagnosis. They usually answer within
        minutes; the question stays open for 6 hours.
      </p>
    </div>
  );
}
