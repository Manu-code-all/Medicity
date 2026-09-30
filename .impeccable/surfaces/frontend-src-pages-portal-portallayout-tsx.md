---
version: 1
slug: "frontend-src-pages-portal-portallayout-tsx"
primary_target: "frontend/src/pages/portal/PortalLayout.tsx"
related_targets: ["frontend/src/pages/doctor/DoctorLayout.tsx","frontend/src/pages/store/StoreLayout.tsx"]
---

# Surface brief: signed-in workspaces (patient portal, doctor workspace, chemist store)

Mode: Operate. Audience: a patient managing a family's care; a doctor working through a clinic day; a chemist answering questions at the counter. Each opens the workspace to do the next thing, many times a day. Constraints: behaviour, copy and tests stay; plain CSS tokens in styles.css; light and dark.

## Direction contract

THESIS: The workspace is the landing page's transit system seen from inside the train: each role rides its own line. It refuses the admin template of a grey link sidebar over a grid of equal stat cards.

OWN-WORLD: Enamel ground (night ground in dark), signage ink, Manrope for every word, Geist Mono for times, prices, tokens and codes. One line colour per role by a data-role switch: patient teal, doctor indigo, chemist marigold with ink text. Station roundels (surface disc, heavy ring) mark navigation state and time. Hairline rules and space make structure; the Float shadow is only for the ticket.

STORY: I see whose line I am on, what is next, and the one action for it.

FIRST VIEWPORT: Left rail: brand roundel, who I am, the line with a station per section (active station ringed in the line colour), account actions at the foot. Main: patient sees the next visit as a ticket (mono date block, doctor, time, Manage) then figures as one ruled ledger row; doctor sees the day as a vertical timeline with a roundel per visit and mono times; chemist sees waiting questions as ruled tickets with mono distance and closing time.

FORM: Rail plus line navigation, code-led, position 1. Seed: none (established world, precise request).

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
