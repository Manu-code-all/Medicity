import { MapPin, NavigationArrow } from "@phosphor-icons/react";
import type { Doctor } from "../api/types";
import { directionsUrl, distanceM, formatDistance, type Point } from "../lib/geo";

/**
 * Where the doctor sees patients: the clinic's name and address, how far it is
 * from the viewer when that is known, and a link that opens directions. A
 * doctor who has not said where shows nothing rather than a guess.
 */
export function ClinicLine({ doctor, from }: { doctor: Pick<Doctor, "clinicName" | "clinicAddress" | "clinicLatitude" | "clinicLongitude">; from: Point | null }) {
  const { clinicName, clinicAddress, clinicLatitude, clinicLongitude } = doctor;
  if (!clinicName && !clinicAddress) return null;
  const at = clinicLatitude != null && clinicLongitude != null ? { lat: clinicLatitude, lng: clinicLongitude } : null;
  const away = from && at ? formatDistance(distanceM(from, at)) : null;

  return (
    <p className="clinic">
      <MapPin size={16} aria-hidden="true" />
      <span className="clinic__where">
        {clinicName && <strong>{clinicName}</strong>}
        {clinicAddress && <span className="muted">{clinicAddress}</span>}
      </span>
      {away && <span className="clinic__away num">{away} away</span>}
      {at && (
        <a className="clinic__directions" href={directionsUrl(at)} target="_blank" rel="noreferrer">
          <NavigationArrow size={14} aria-hidden="true" /> Directions
        </a>
      )}
    </p>
  );
}
