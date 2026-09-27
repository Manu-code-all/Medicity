import { Link } from "react-router-dom";
import { ArrowRight, Phone, Warning } from "@phosphor-icons/react";
import { BODY_TAXONOMY, DURATIONS, specialistPhrase, type DurationId, type Recommendation } from "./taxonomy";

function doctorsLink(specialty: string, zone: string) {
  return `/doctors?${new URLSearchParams({ specialty, zone })}`;
}

/** The answer: which kind of doctor, why, and the way to book one; or go to emergency now. */
export function ClinicalRecommendationCard({
  regionId,
  duration,
  recommendation,
  onRestart,
}: {
  regionId: string;
  duration: DurationId | null;
  recommendation: Recommendation;
  onRestart: () => void;
}) {
  if (recommendation.emergency) {
    return (
      <section className="bm-result bm-result--emergency" role="alert">
        <h3>
          <Warning size={24} weight="fill" aria-hidden="true" /> Get emergency care now
        </h3>
        <p>
          These symptoms may need urgent evaluation. Go to the nearest hospital emergency department, or call for
          help now. Do not wait for an appointment.
        </p>
        <div className="bm-result__calls">
          <a className="lm-button" href="tel:112">
            <Phone size={20} weight="bold" aria-hidden="true" /> Call 112
          </a>
          <a className="lm-button lm-button--quiet" href="tel:108">
            Call 108 for an ambulance
          </a>
        </div>
        <button type="button" className="lm-link-button" onClick={onRestart}>
          Start again
        </button>
      </section>
    );
  }

  const area = BODY_TAXONOMY[regionId]?.label.toLowerCase();
  const since = DURATIONS.find((d) => d.id === duration)?.label.toLowerCase();
  return (
    <section className="bm-result" aria-live="polite">
      <p className="bm-result__lead">For your {area}{since ? `, ${since}` : ""}</p>
      <h3>See {specialistPhrase(recommendation.specialty)}</h3>
      <p>{recommendation.rationale}</p>
      <Link to={doctorsLink(recommendation.specialty, regionId)} className="lm-button lm-button--block">
        Find {recommendation.specialty} doctors
        <ArrowRight size={16} weight="bold" aria-hidden="true" />
      </Link>
      <p className="bm-result__alt">
        Or start with{" "}
        <Link to={doctorsLink(recommendation.alternative, regionId)}>{specialistPhrase(recommendation.alternative)}</Link>
        .
      </p>
      <p className="bm-result__note">
        A guide to the right kind of doctor, not a diagnosis. If you feel very unwell, go to an emergency department.
      </p>
      <button type="button" className="lm-link-button" onClick={onRestart}>
        Start again
      </button>
    </section>
  );
}
