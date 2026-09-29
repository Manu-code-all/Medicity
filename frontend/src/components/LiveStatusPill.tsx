import { useQuery } from "@tanstack/react-query";
import { doctors } from "../api/endpoints";

const TIP = "Estimated from the visits the doctor has not finished yet. Use it to time your arrival.";

/** "Doctor is on time" or "Running about 20 min behind", refreshed each minute. */
export function LiveStatusPill({ doctorId }: { doctorId: string }) {
  const status = useQuery({
    queryKey: ["live-status", doctorId],
    queryFn: () => doctors.liveStatus(doctorId),
    refetchInterval: 60_000,
  });
  if (!status.data) return null;
  const late = status.data.state === "RUNNING_LATE";
  return (
    <p className={late ? "live-pill live-pill--late" : "live-pill"} title={TIP} role="status">
      <span aria-hidden="true">{late ? "🟡" : "🟢"}</span>{" "}
      {late ? `Doctor is running about ${status.data.delayMinutes} min behind` : "Doctor is on time"}
      <span className="sr-only"> ({TIP})</span>
    </p>
  );
}
