import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { homeFor, useAuth } from "../auth/context";
import { prefersReducedMotion } from "../lib/reveal";

/** The two pages the capsule turns through, one after the other. */
const PAGES = ["Find the right doctor, then your medicines nearby.", "From prescription to medicines in hand, near you."];
const TURN_MS = 5000;

/**
 * The landing page's floating capsule: the brand, the two lines Medicity
 * stands for turning one after the other, and the way in. It holds still
 * while a pointer or the keyboard is on it, and does not turn at all for
 * anyone who has asked for less motion.
 */
export function IslandNav() {
  const { session } = useAuth();
  const [page, setPage] = useState(0);
  const [held, setHeld] = useState(false);

  useEffect(() => {
    if (held || prefersReducedMotion()) return;
    const timer = window.setInterval(() => setPage((p) => (p + 1) % PAGES.length), TURN_MS);
    return () => window.clearInterval(timer);
  }, [held, page]);

  const account = session
    ? { to: homeFor(session.role), label: "Open my account" }
    : { to: "/login", label: "Sign in" };

  return (
    <header className="lm-nav">
      <nav
        className="lm-nav__pill"
        aria-label="Main"
        onMouseEnter={() => setHeld(true)}
        onMouseLeave={() => setHeld(false)}
        onFocus={() => setHeld(true)}
        onBlur={() => setHeld(false)}
      >
        <Link to="/" className="lm-brand" aria-label="Medicity home">
          <span className="lm-brand__mark" aria-hidden="true" />
          Medicity
        </Link>

        <div className="lm-pages" role="group" aria-roledescription="carousel" aria-label="What Medicity does">
          <div className="lm-pages__stage">
            {PAGES.map((text, i) => (
              <p key={text} className="lm-pages__page" data-active={i === page} aria-hidden={i !== page}>
                {text}
              </p>
            ))}
          </div>
          <div className="lm-pages__dots">
            {PAGES.map((_, i) => (
              <button
                key={i}
                type="button"
                className="lm-pages__dot"
                aria-label={`Page ${i + 1} of ${PAGES.length}`}
                aria-current={i === page}
                onClick={() => setPage(i)}
              />
            ))}
          </div>
        </div>

        <Link to={account.to} className="lm-nav__cta">
          {account.label}
        </Link>
      </nav>
    </header>
  );
}
