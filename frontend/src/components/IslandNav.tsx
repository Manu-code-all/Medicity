import { useEffect, useState } from "react";
import { Link, NavLink, useLocation } from "react-router-dom";
import { homeFor, useAuth } from "../auth/context";

const LINKS = [
  { to: "/#line", label: "How it works" },
  { to: "/doctors", label: "Find a doctor" },
];

/** The landing page's floating navigation; a full-screen sheet on phones. */
export function IslandNav() {
  const { session } = useAuth();
  const [open, setOpen] = useState(false);
  const location = useLocation();

  // Close the sheet on navigation, and let Escape close it.
  useEffect(() => setOpen(false), [location.pathname, location.hash]);
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    document.addEventListener("keydown", onKey);
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = "";
    };
  }, [open]);

  const account = session
    ? { to: homeFor(session.role), label: "Open my account" }
    : { to: "/login", label: "Sign in" };

  return (
    <header className="lm-nav" data-open={open}>
      <nav className="lm-nav__pill" aria-label="Main">
        <Link to="/" className="lm-brand" aria-label="Medicity home">
          <span className="lm-brand__mark" aria-hidden="true" />
          Medicity
        </Link>
        <ul className="lm-nav__links">
          {LINKS.map((l) => (
            <li key={l.to}>
              <NavLink to={l.to}>{l.label}</NavLink>
            </li>
          ))}
          <li>
            <NavLink to={account.to}>{account.label}</NavLink>
          </li>
        </ul>
        <button
          type="button"
          className="lm-burger"
          aria-expanded={open}
          aria-controls="lm-sheet"
          aria-label={open ? "Close menu" : "Open menu"}
          onClick={() => setOpen((o) => !o)}
        >
          <span />
          <span />
        </button>
      </nav>

      {/* Kept in the DOM so it can animate; visibility hides it from keyboards and readers when closed. */}
      <div id="lm-sheet" className="lm-sheet">
        <ul>
          {[...LINKS, account].map((l, i) => (
            <li key={l.label} style={{ transitionDelay: `${100 + i * 50}ms` }}>
              <Link to={l.to} onClick={() => setOpen(false)}>
                {l.label}
              </Link>
            </li>
          ))}
        </ul>
      </div>
    </header>
  );
}
