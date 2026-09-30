import { useEffect, useMemo, useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router-dom";
import { ArrowRight, MapPin, Star } from "@phosphor-icons/react";
import { doctors } from "../api/endpoints";
import type { Doctor, Slot } from "../api/types";
import { BODY_TAXONOMY, specialistPhrase } from "../components/bodymap/taxonomy";
import { ClinicLine } from "../components/ClinicLine";
import { distanceM } from "../lib/geo";
import { initials } from "../lib/format";
import { useMyLocation } from "../lib/useMyLocation";
import { InsurerTags, PriceList } from "./DoctorOffers";

/**
 * The directory. Its filters live in the URL (?specialty=, ?q=, and ?zone=
 * when the body guide sent the visitor here), so the landing page's two doors
 * link straight into a filtered list, and a filtered list can be shared.
 */
export function DoctorsPage() {
  const [params, setParams] = useSearchParams();
  const specialization = params.get("specialty") ?? "";
  const insurance = params.get("insurance") ?? "";
  const zone = params.get("zone");
  const [nameQuery, setNameQuery] = useState(params.get("q") ?? "");
  const [sort, setSort] = useState<"match" | "nearest">("match");
  const me = useMyLocation();

  // Typing updates the URL after a pause, not on every keystroke.
  useEffect(() => {
    const t = window.setTimeout(() => {
      setParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          if (nameQuery.trim()) next.set("q", nameQuery.trim());
          else next.delete("q");
          return next;
        },
        { replace: true },
      );
    }, 300);
    return () => window.clearTimeout(t);
  }, [nameQuery, setParams]);

  const q = params.get("q") ?? "";
  const query = useQuery({
    queryKey: ["doctors", specialization, q, insurance],
    queryFn: () => doctors.search(specialization || undefined, q || undefined, insurance || undefined),
    // Keeps the previous results on screen while a new filter loads, so the
    // list does not collapse to a spinner on every keystroke.
    placeholderData: keepPreviousData,
  });
  const specialties = useQuery({ queryKey: ["doctors", "specialties"], queryFn: doctors.specialties, staleTime: 5 * 60_000 });
  const insurers = useQuery({ queryKey: ["doctors", "insurers"], queryFn: doctors.insurers, staleTime: 60 * 60_000 });

  function setInsurance(value: string) {
    setParams((prev) => {
      const next = new URLSearchParams(prev);
      if (value) next.set("insurance", value);
      else next.delete("insurance");
      return next;
    });
  }

  function setSpecialization(value: string) {
    setParams((prev) => {
      const next = new URLSearchParams(prev);
      if (value) next.set("specialty", value);
      else next.delete("specialty");
      next.delete("zone");
      return next;
    });
  }

  // Nearest first sorts what is on the page; a doctor with no clinic location goes last.
  const listed = useMemo(() => {
    const rows = query.data?.content ?? [];
    if (sort !== "nearest" || !me.point) return rows;
    const from = me.point;
    const away = (d: Doctor) =>
      d.clinicLatitude != null && d.clinicLongitude != null
        ? distanceM(from, { lat: d.clinicLatitude, lng: d.clinicLongitude })
        : Number.POSITIVE_INFINITY;
    return [...rows].sort((a, b) => away(a) - away(b));
  }, [query.data, sort, me.point]);

  const area = zone ? BODY_TAXONOMY[zone] : undefined;
  const fallback = area && area.routing.primarySpecialty !== specialization ? area.routing.primarySpecialty : area?.routing.secondarySpecialty;

  return (
    <section className="directory">
      <header className="directory__head">
        <h1>Find a doctor</h1>
        <p className="muted">Real open times, what each visit costs, and how far the clinic is from you.</p>
      </header>

      {area && specialization && (
        <p className="notice" role="status">
          From the body guide: <strong>{area.label}</strong>. Showing {specialistPhrase(specialization).replace(/^an? /, "")}s.{" "}
          <Link to="/#main">Change the answer</Link>
        </p>
      )}

      <div className="filters">
        <label htmlFor="q" className="sr-only">
          Search by name or speciality
        </label>
        <input
          id="q"
          placeholder="Search by name or speciality"
          value={nameQuery}
          onChange={(e) => setNameQuery(e.target.value)}
        />

        <label htmlFor="spec" className="sr-only">
          Specialization
        </label>
        <select id="spec" value={specialization} onChange={(e) => setSpecialization(e.target.value)}>
          <option value="">All specializations</option>
          {specialties.data?.map((s) => (
            <option key={s.name} value={s.name}>
              {s.name} ({s.doctors})
            </option>
          ))}
          {specialization && !specialties.data?.some((s) => s.name === specialization) && (
            <option value={specialization}>{specialization}</option>
          )}
        </select>

        <label htmlFor="insurance" className="sr-only">
          Insurance
        </label>
        <select id="insurance" value={insurance} onChange={(e) => setInsurance(e.target.value)}>
          <option value="">Any insurance</option>
          {insurers.data?.map((i) => (
            <option key={i.name} value={i.name}>
              {i.name}
            </option>
          ))}
        </select>
      </div>

      <div className="directory__bar">
        <p className="directory__where">
          <MapPin size={16} aria-hidden="true" />
          {me.point ? (
            <span>{me.source === "saved" ? "Distances from your saved location" : "Distances from where you are now"}</span>
          ) : (
            <>
              <span>See how far each clinic is.</span>
              <button type="button" className="link" onClick={me.locate} disabled={me.status === "locating"}>
                {me.status === "locating" ? "Finding you…" : "Use my location"}
              </button>
            </>
          )}
          {me.status === "refused" && <span className="error">Your browser did not share the location.</span>}
        </p>
        <label className="directory__sort">
          <span className="sr-only">Sort doctors</span>
          <select value={sort} onChange={(e) => setSort(e.target.value as "match" | "nearest")} aria-label="Sort doctors">
            <option value="match">Best match</option>
            <option value="nearest" disabled={!me.point}>
              Nearest first
            </option>
          </select>
        </label>
      </div>

      {query.isPending && <p className="muted">Loading…</p>}
      {query.isError && <p className="error">Could not load doctors.</p>}

      {query.data && query.data.content.length === 0 && (
        <div className="card empty">
          <p>No doctors match that search.</p>
          {area && fallback && fallback !== specialization && (
            <p>
              <button type="button" className="link" onClick={() => setSpecialization(fallback)}>
                See {specialistPhrase(fallback)} instead
              </button>
            </p>
          )}
        </div>
      )}

      <ul className="doctor-list">
        {listed.map((doctor) => (
          <li key={doctor.id} className="dcard">
            <div className="dcard__top">
              <div className="avatar avatar--lg" aria-hidden="true">
                {initials(doctor.fullName)}
              </div>
              <div className="dcard__who">
                <h2>{doctor.fullName}</h2>
                <p className="muted">
                  {doctor.specialization} · {doctor.yearsExperience} yrs experience
                </p>
                {doctor.reviewCount ? (
                  <p className="rating">
                    <Star size={14} weight="fill" aria-hidden="true" /> {doctor.rating?.toFixed(1)}{" "}
                    <Link to={`/doctors/${doctor.id}/book#reviews`} className="muted">
                      {doctor.reviewCount} review{doctor.reviewCount > 1 ? "s" : ""} from visits
                    </Link>
                  </p>
                ) : null}
                <ClinicLine doctor={doctor} from={me.point} />
              </div>
              <div className="dcard__fee">
                <span className="dcard__feeLabel">Consultation</span>
                <p className="fee num">₹{doctor.consultationFee}</p>
              </div>
            </div>

            {doctor.bio && <p className="dcard__bio">{doctor.bio}</p>}
            <InsurerTags insurers={doctor.insurers ?? []} />
            <PriceList doctor={doctor} />

            <div className="dcard__foot">
              {doctor.nextSlots.length > 0 ? (
                <div className="dcard__times">
                  <p className="dcard__label">Next available</p>
                  <ul className="next-days" aria-label={`Next free times with ${doctor.fullName}`}>
                    {groupByDay(doctor.nextSlots).map((day) => (
                      <li key={day.key} className="next-days__day">
                        <span className="next-days__name">{day.label}</span>
                        <span className="next-days__times">
                          {day.slots.map((slot) => (
                            <Link key={slot.id} className="slot-pill" to={`/doctors/${doctor.id}/book?slot=${slot.id}`}>
                              {timeLabel(slot.startsAt)}
                            </Link>
                          ))}
                        </span>
                      </li>
                    ))}
                  </ul>
                </div>
              ) : (
                <p className="muted small next-slots__none">
                  No free times in the next two weeks.{" "}
                  <Link to={`/doctors/${doctor.id}/book#waitlist`}>Notify me when a time opens</Link>
                </p>
              )}
              <div className="dcard__actions">
                <Link className="button" to={`/doctors/${doctor.id}/book`}>
                  {doctor.nextSlots.length > 0 ? "See all times" : "Book"}
                </Link>
                <Link className="button button--quiet" to={`/doctors/${doctor.id}/walk-in`}>
                  Walk in today
                </Link>
              </div>
            </div>
          </li>
        ))}
      </ul>
      {listed.length > 0 && (
        <p className="muted small directory__note">
          Distances are in a straight line and will be longer by road.{" "}
          <Link className="arrow-link" to="/portal/profile">
            Change your saved location <ArrowRight size={14} aria-hidden="true" />
          </Link>
        </p>
      )}
    </section>
  );
}

interface Day {
  key: string;
  label: string;
  slots: Slot[];
}

/** The next free times, one row per day: "Tomorrow  10:00 am  10:30 am". */
function groupByDay(slots: Slot[], now = new Date()): Day[] {
  const days: Day[] = [];
  for (const slot of slots) {
    const at = new Date(slot.startsAt);
    const key = `${at.getFullYear()}-${at.getMonth()}-${at.getDate()}`;
    let day = days.find((d) => d.key === key);
    if (!day) {
      day = { key, label: dayLabel(at, now), slots: [] };
      days.push(day);
    }
    day.slots.push(slot);
  }
  return days;
}

function dayLabel(at: Date, now: Date): string {
  const days = Math.round((startOfDay(at) - startOfDay(now)) / 86_400_000);
  if (days === 0) return "Today";
  if (days === 1) return "Tomorrow";
  return at.toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" });
}

function timeLabel(iso: string): string {
  return new Date(iso).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });
}

function startOfDay(d: Date): number {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
}
