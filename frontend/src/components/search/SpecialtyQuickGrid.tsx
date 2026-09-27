import { Link } from "react-router-dom";
import { Baby, Bone, FirstAidKit, Heartbeat, Tooth, HandPalm } from "@phosphor-icons/react";

const QUICK = [
  { name: "General Medicine", label: "General physician", Icon: FirstAidKit },
  { name: "Dermatology", label: "Skin", Icon: HandPalm },
  { name: "Orthopaedics", label: "Bones and joints", Icon: Bone },
  { name: "Paediatrics", label: "Children", Icon: Baby },
  { name: "Dentistry", label: "Teeth", Icon: Tooth },
  { name: "Cardiology", label: "Heart", Icon: Heartbeat },
];

/** The six most asked-for specialities, one tap each. */
export function SpecialtyQuickGrid() {
  return (
    <ul className="quick-grid" aria-label="Popular specialities">
      {QUICK.map(({ name, label, Icon }) => (
        <li key={name}>
          <Link to={`/doctors?${new URLSearchParams({ specialty: name })}`} className="quick-grid__item">
            <Icon size={20} weight="bold" aria-hidden="true" />
            <span>{label}</span>
          </Link>
        </li>
      ))}
    </ul>
  );
}
