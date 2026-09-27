---
version: 1
slug: "frontend-src-pages-landingpage-tsx"
primary_target: "frontend/src/pages/LandingPage.tsx"
related_targets: ["frontend/src/pages/LoginPage.tsx"]
---

# Landing and sign-in pages

Scope: `/` (landing, Persuade) and the three sign-in pages `/login`,
`/login/doctor`, `/login/chemist` (Persuade into Operate). Patient-first, with a
one-click demo path for evaluators. The portal, doctor and store workspaces are
out of scope and keep the incumbent tokens.

Audience and action: a patient in Bengaluru deciding whether to book here; the
one primary action is "Book a visit" (to /doctors). Evaluators take "Try the
demo". Proof: real demo data only (doctors, five Indiranagar chemists, real
medicines and prices). No invented users, ratings or numbers.

## Direction contract

THESIS: Care is one line with five stations: book, visit, prescription,
chemists nearby, pick up. The page refuses the category's search box and
service-card grid; the journey itself is the hero.

OWN-WORLD: Enamel signage. Flat off-white ground, deep ink navy type, solid
thick line bands with round station markers (white fill, ink ring). One line
colour per role: patient teal, doctor indigo, chemist marigold. Labels set flush
beside stations like platform signs, with a small second-language line (Hindi)
under key station names. Manrope for all type (Geist was replaced after the
detector flagged it as overused; Manrope is on the pinned font list), Geist
Mono only for codes, prices and distances. No gradients, no glass, no cards-as-structure.

STORY: The visitor sees their own neighbourhood with chemists answering, learns
the five steps by riding the line down the page, believes it because every
station shows a real screen fragment with real demo data, and books (or tries
the demo as Meera, Dr. Rao or a chemist).

FIRST VIEWPORT: Left column (max 680px): headline "From prescription to
medicines in hand, near you.", one sentence subhead, primary button "Book a
visit", a quiet "Try the demo" link. Right: a line map of Indiranagar with 1 and 3
km rings (the 5 km ring falls outside the frame at a scale where the nearest
stores stay legible, so the caption states the 3 km search instead) around the patient, five chemist stations lighting in turn with
their answer (has it, price, distance). The teal line enters the map from the
left edge of the copy.

FORM: Neighbourhood line map, position 4 of the ordered list, seed key
7c1358be. Raises: sign-in role switch slides the line band (sliding planes);
pick-up code in ghost-cell numerals (segment display); every datum carries its
source (quilt). Signature interaction: the chemists answering on the map, then
the line drawing itself station by station as the visitor scrolls.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
