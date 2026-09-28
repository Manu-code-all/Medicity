---
name: Medicity
description: From prescription to medicines in hand, near you. Public surfaces drawn as enamel transit signage, one coloured line per role.
colors:
  enamel-ground: "#f7f7f2"
  enamel-surface: "#ffffff"
  enamel-raised: "#efefe8"
  hairline-rule: "#d9dbd3"
  signage-ink: "#10202b"
  signage-ink-muted: "#4a5a66"
  heading-from: "#000000"
  heading-to: "#666666"
  patient-teal: "#0e8a78"
  patient-band: "#0b6f62"
  patient-text: "#0a6f61"
  doctor-indigo: "#3b3fb6"
  chemist-marigold: "#f0a202"
  chemist-text: "#8a5a00"
  on-band-light: "#ffffff"
  on-band-dark: "#10202b"
  alert-red: "#b3261e"
  night-ground: "#181818"
  night-surface: "#1f1f1f"
  night-raised: "#272727"
  night-rule: "#313131"
  night-ink: "#f3f3ee"
  night-ink-muted: "#a8adb0"
  night-heading-from: "#ffffff"
  night-heading-to: "#9b9b9b"
  night-patient-teal: "#2fbfa6"
  night-patient-text: "#4fd3bb"
  night-doctor-indigo: "#8e92f5"
  night-doctor-text: "#a9acf8"
  night-chemist-text: "#f5b82e"
typography:
  display:
    fontFamily: "Manrope, system-ui, sans-serif"
    fontSize: "60px"
    fontWeight: 600
    lineHeight: "60px"
    letterSpacing: "-0.03em"
    fontFeature: "\"ss01\""
  headline:
    fontFamily: "Manrope, system-ui, sans-serif"
    fontSize: "48px"
    fontWeight: 600
    lineHeight: "48px"
    letterSpacing: "-0.02em"
    fontFeature: "\"ss01\""
  title:
    fontFamily: "Manrope, system-ui, sans-serif"
    fontSize: "30px"
    fontWeight: 600
    lineHeight: "36px"
    letterSpacing: "-0.01em"
    fontFeature: "\"ss01\""
  subtitle:
    fontFamily: "Manrope, system-ui, sans-serif"
    fontSize: "18px"
    fontWeight: 600
    lineHeight: "28px"
    fontFeature: "\"ss01\""
  lede:
    fontFamily: "Manrope, system-ui, sans-serif"
    fontSize: "18px"
    fontWeight: 400
    lineHeight: "28px"
    fontFeature: "\"ss01\""
  body:
    fontFamily: "Manrope, system-ui, sans-serif"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: "24px"
    fontFeature: "\"ss01\""
  label:
    fontFamily: "Manrope, system-ui, sans-serif"
    fontSize: "14px"
    fontWeight: 600
    lineHeight: "20px"
    fontFeature: "\"ss01\""
  caption:
    fontFamily: "Manrope, system-ui, sans-serif"
    fontSize: "12px"
    fontWeight: 600
    lineHeight: "16px"
    fontFeature: "\"ss01\""
  mono:
    fontFamily: "Geist Mono, ui-monospace, monospace"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: "20px"
    fontFeature: "\"tnum\""
  mono-display:
    fontFamily: "Geist Mono, ui-monospace, monospace"
    fontSize: "36px"
    fontWeight: 500
    lineHeight: "40px"
    fontFeature: "\"tnum\""
rounded:
  focus: "4px"
  md: "8px"
  lg: "12px"
  xl: "16px"
  pill: "9999px"
spacing:
  "2": "2px"
  "4": "4px"
  "8": "8px"
  "12": "12px"
  "16": "16px"
  "24": "24px"
  "32": "32px"
  "40": "40px"
  "48": "48px"
  "64": "64px"
  "80": "80px"
  "96": "96px"
components:
  button-primary:
    backgroundColor: "{colors.patient-band}"
    textColor: "{colors.on-band-light}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: "8px 12px"
    height: "40px"
  button-primary-doctor:
    backgroundColor: "{colors.doctor-indigo}"
    textColor: "{colors.on-band-light}"
    rounded: "{rounded.md}"
    padding: "8px 12px"
  button-primary-chemist:
    backgroundColor: "{colors.chemist-marigold}"
    textColor: "{colors.on-band-dark}"
    rounded: "{rounded.md}"
    padding: "8px 12px"
  button-primary-sm:
    backgroundColor: "{colors.patient-band}"
    textColor: "{colors.on-band-light}"
    typography: "{typography.label}"
    rounded: "{rounded.md}"
    padding: "4px 12px"
    height: "32px"
  text-link:
    textColor: "{colors.patient-text}"
    typography: "{typography.body}"
  input:
    backgroundColor: "{colors.enamel-surface}"
    textColor: "{colors.signage-ink}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: "8px 12px"
    height: "40px"
  chip:
    textColor: "{colors.patient-text}"
    typography: "{typography.caption}"
    rounded: "{rounded.pill}"
    padding: "2px 8px"
  chip-solid:
    backgroundColor: "{colors.patient-band}"
    textColor: "{colors.on-band-light}"
    typography: "{typography.caption}"
    rounded: "{rounded.pill}"
    padding: "2px 8px"
  screen-fragment:
    backgroundColor: "{colors.enamel-surface}"
    textColor: "{colors.signage-ink}"
    rounded: "{rounded.xl}"
    padding: "24px"
  role-switch:
    backgroundColor: "{colors.enamel-raised}"
    rounded: "{rounded.pill}"
    padding: "4px"
  role-switch-tab:
    textColor: "{colors.signage-ink-muted}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "8px 12px"
  role-switch-tab-active:
    backgroundColor: "{colors.patient-band}"
    textColor: "{colors.on-band-light}"
    rounded: "{rounded.pill}"
  demo-card:
    textColor: "{colors.signage-ink}"
    rounded: "{rounded.lg}"
    padding: "12px 16px"
  nav-pill:
    backgroundColor: "{colors.enamel-surface}"
    rounded: "{rounded.pill}"
    padding: "8px 8px 8px 16px"
  nav-link:
    textColor: "{colors.signage-ink-muted}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "4px 12px"
---

# Design System: Medicity

## Overview

**Creative North Star: "Enamel Signage"**

The public face of Medicity is a transit line. Care is one line with five stations (book, visit, prescription, chemists nearby, pick up), and every public surface is drawn the way a well kept metro system signs its platforms: a flat off white ground, deep ink navy lettering, thick solid line bands in one colour, and round station markers with a white fill and a heavy ring. Labels sit flush beside their stations like platform signs, and key station names carry a small Hindi line underneath. The mood is calm, plain and exact; the journey itself is the illustration, and real screen fragments with real demo data stand in for decoration.

Colour is wayfinding, not ornament. Each role owns one line colour (patient teal, doctor indigo, chemist marigold), and a surface switches its whole accent by switching role, so the sign-in page for a chemist is literally painted in the chemist line. Everything else stays neutral ink on enamel. Depth is reserved for the few things that float above the map: the navigation island, the screen fragments at each station, and a hovered button.

**Scope.** This world governs the public surfaces only: the landing page (`/`) and the three sign-in pages (`/login`, `/login/doctor`, `/login/chemist`). Its tokens live in `frontend/src/landing.css`, scoped under the `.lm` class, in light and dark. The signed in workspaces (patient portal, doctor workspace, chemist store, admin) are out of scope and keep the older incumbent tokens in `frontend/src/styles.css`. Do not mix the two token sets on one surface, and do not apply `.lm` tokens inside the workspaces without a deliberate redesign of them.

The landing page opens with **two doors** before the line: a body guide for visitors who only know where it hurts, and a search box for those who know whom they want. Both are panels on the enamel ground in the same system; the line and its map follow directly below.

**Key Characteristics:**
- One line colour per role, carried by a single `data-role` switch.
- Round station markers (white fill, heavy ring) on thick rounded line bands.
- Manrope for every word; Geist Mono only for codes, prices, times and distances.
- Fixed type scale and fixed spacing scale; nothing in between.
- One motion curve, `cubic-bezier(0.32, 0.72, 0, 1)`, at 700ms.
- Flat enamel ground; soft ambient shadow only on things that float.

## Colors

A neutral enamel and ink base, with three saturated line colours that each belong to exactly one role.

### Primary
- **Patient Line Teal** (`patient-teal`): the default line. Map trunk, search rings, journey track fill, reached station rings, focus outlines, caret and text selection on patient surfaces.
- **Patient Band Teal** (`patient-band`): the deeper fill for anything carrying white text: primary buttons, the sign-in side band, solid chips, the picked time slot, map tags. It keeps white text readable where the line teal would not.
- **Patient Text Teal** (`patient-text`): teal for type on the enamel ground: text links, station names, outline chip text.

### Secondary
- **Doctor Line Indigo** (`doctor-indigo`): the doctor role's line, band and text colour in light mode. Appears on the doctor sign-in page and the doctor line in the "three lines" section.

### Tertiary
- **Chemist Line Marigold** (`chemist-marigold`): the chemist role's line and band, and the "partly available" state on the map. Always paired with ink text (`on-band-dark`), never white.
- **Chemist Text Ochre** (`chemist-text`): the darkened marigold used whenever the chemist colour must be read as text on enamel.

### Neutral
- **Enamel Ground** (`enamel-ground`): page background; also backs map labels so they read as signs cut out of the ground.
- **Enamel Surface** (`enamel-surface`): screen fragments, inputs, the tagline and closing bands, the white fill of roundels.
- **Enamel Raised** (`enamel-raised`): recessed wells: the role switch track, unavailable slots, pick up code cells, hover fills on quiet links.
- **Hairline Rule** (`hairline-rule`): 1px borders and dividers, the unreached journey track, faint street lines on the map.
- **Signage Ink** (`signage-ink`): all primary text, the ring of an unreached roundel.
- **Signage Ink Muted** (`signage-ink-muted`): secondary text, captions, Hindi lines, inactive nav links.
- **Alert Red** (`alert-red`): sign-in errors (as a border and an 8% tint) and destructive actions shown inside screen fragments ("Cancel visit").
- **Night set** (`night-*`): the dark theme, applied by `prefers-color-scheme: dark` unless `data-theme="light"`, or forced by `data-theme="dark"`. Line colours lighten for contrast on the dark ground; band fills that carry white text (`patient-band`, `doctor-indigo`, `chemist-marigold`) stay the same.

### Named Rules
**The One Line Per Role Rule.** Teal is the patient, indigo is the doctor, marigold is the chemist. A surface gets exactly one active line colour, chosen by `data-role` on the `.lm` root or a descendant. Never use a role's colour to decorate content that does not belong to that role.

**The Band, Line, Text Rule.** Each role has three tones: a band tone for fills under text, a line tone for strokes and rings, and a text tone for type on enamel. Use the variable for the job (`--lm-band`, `--lm-line`, `--lm-line-text`), never the raw line colour as text. Marigold is never text on enamel and never carries white text.

**The Enamel Ground Rule.** Backgrounds are flat solid colour. No background gradients anywhere.

## Typography

**Display Font:** Manrope (with system-ui, sans-serif)
**Body Font:** Manrope (with system-ui, sans-serif), stylistic set `ss01` on
**Label/Mono Font:** Geist Mono (with ui-monospace, monospace), tabular numerals

**Character:** Manrope is the signage face: open, even and legible at a distance, heavy enough at 600 to read as painted enamel. Geist Mono is the ticket printer: it appears only where a value must be read exactly (codes, prices, times, distances).

### Hierarchy
- **Display** (600, 60px / 60px, -0.03em): the hero heading only. Steps down to 48px under 1024px and 36px / 40px under 640px.
- **Headline** (600, 48px / 48px, -0.02em): section headings, the tagline, the closing call. 30px / 36px on phones.
- **Title** (600, 30px / 36px, -0.01em): station names, role line names, sign-in page titles and the sign-in band pitch; 24px / 32px on phones. The phone menu sheet uses this size for its links.
- **Subtitle** (600, 18px / 28px): station step titles, FAQ questions, the brand wordmark (at 700), the sign-in band's stop list (at 500).
- **Lede** (400, 18px / 28px): the hero sentence under the display heading; 16px / 24px on phones.
- **Body** (400, 16px / 24px): running text, capped at 60 to 65ch.
- **Label** (600, 14px / 20px): buttons in compact form, nav links (at 500), form labels, role switch tabs, captions under the map.
- **Caption** (600, 12px / 16px): chips, map tags and ring labels, footer note.
- **Mono** (400 to 500, 14px / 20px; 36px / 40px for pick up code cells): times, prices, distances, codes.

### Named Rules
**The Fixed Scale Rule.** Type sizes come only from 12, 14, 16, 18, 20, 24, 30, 36, 48 and 60px, each with its paired line height (16, 20, 24, 28, 28, 32, 36, 40, 48, 60). No sizes in between, no fluid clamps.

**The Two Families Rule.** Manrope sets every word. Geist Mono sets only values a person must read or type exactly. Nothing else enters.

**The Hero Gradient Exception.** The hero heading alone is filled with a horizontal ink gradient (`heading-from` to `heading-to`), because the landing page brief pins it. This is not a general device: no other heading, text or surface may use gradient fill.

**The Plain Voice Rule.** Copy is sentence case, plain and direct, with no hyphens.

## Layout

Content sits in a centred column of 1120px maximum with 32px side padding (16px on phones); reading text caps at 680px. Sections breathe on 96px vertical padding (64px on phones). The hero is a two column grid (copy about 1.15 : map 1) with a 48px gap that stacks to one column under 1024px. The journey is a three column station grid (48px track, sign, screen fragment) with a 48px gutter and 80px between stations; under 1024px the fragment drops below the sign, and under 640px the track column narrows to 40px. The sign-in pages split 5 : 7, a sticky full height role band on the left and the form (400px maximum) on the right; under 860px the band collapses to a short header and its stop list hides.

Spacing comes only from 2, 4, 8, 12, 16, 24, 32, 40, 48, 64, 80 and 96px. Breakpoints: 1023px (stack the hero and journey), 860px (nav becomes a burger and sheet; sign-in stacks), 640px (phone type and padding; the map drops its floating labels for a plain answers list).

### Named Rules
**The Fixed Rhythm Rule.** Every margin, padding and gap is a step on the spacing scale. If a value is not on the scale, pick the nearest step.

## Elevation & Depth

The ground is flat enamel; depth belongs only to things that float above it. One ambient shadow token, a tight contact shadow plus a wide soft spread, lifts the navigation island, the screen fragments at each station and a hovered demo card. The primary button carries a small shadow tinted with its own band colour, which deepens and lifts 2px on hover. The floating navigation island and the phone menu sheet are the only frosted surfaces (80% surface colour with a background blur); everything else is opaque.

### Shadow Vocabulary
- **Float** (`box-shadow: 0 2px 4px rgba(16, 32, 43, 0.04), 0 12px 32px rgba(16, 32, 43, 0.08)`; dark: `0 2px 4px rgba(0,0,0,0.3), 0 12px 32px rgba(0,0,0,0.35)`): nav island, screen fragments, demo card on hover.
- **Band lift** (`box-shadow: 0 1px 2px rgba(16, 32, 43, 0.12), 0 4px 12px color-mix(in srgb, var(--lm-band) 24%, transparent)`): primary button at rest; 8px / 24px at 32% on hover.
- **Focus halo** (`box-shadow: 0 0 0 4px color-mix(in srgb, var(--lm-line) 24%, transparent)`): focused input.
- **Home halo** (`box-shadow: 0 0 0 6px color-mix(in srgb, var(--lm-patient) 18%, transparent)`): the patient's home roundel on the map.

### Named Rules
**The Float Only Rule.** Shadows mark something floating over the map or responding to a pointer. Sections, bands and the ground never cast shadows; structure is made with rules and space, not cards.

**The One Frosted Island Rule.** Background blur is for the floating navigation and its phone sheet only.

## Shapes

The form language is the transit diagram: fully rounded line bands (8px thick, pill ends), circles for stations, and gently rounded rectangles for anything a person touches. Roundels are a white (surface) disc with a heavy ring: 20px with a 4px ring by default, 28px with a 6px ring for journey stations, home and role line heads, 12px with a 3px ring as list bullets, 16px with a 3px ring in the phone answers list. A roundel's ring is ink until reached, then takes the line colour; a filled roundel means "has it". The brand mark is the same roundel on the line colour, and the favicon is a roundel on a short teal band.

Radii: 4px for focus rings, 8px for buttons, inputs, slots and code cells, 12px for the demo card, 16px for screen fragments, and a full pill for chips, the nav island, nav links, the role switch and every line band.

### Named Rules
**The Roundel Rule.** A circle always means a station or a status on the line. Do not use circles as generic decoration or avatar frames in this world.

## Components

Every component takes its accent from the active line through `--lm-line`, `--lm-band` and `--lm-line-text`, so one `data-role` switch repaints it.

### Buttons
- **Shape:** gently rounded (8px).
- **Primary:** band fill, on band text (white, or ink on marigold), Manrope 16px / 24px at 600, padding 8px 12px, 40px minimum height, band tinted lift shadow. Compact variant: 14px / 20px, 4px 12px, 32px high (nav call to action). Block variant fills its column (sign-in submit).
- **Hover / Focus:** lifts 2px with a deeper tinted shadow over 700ms; presses to 0.98 scale; focus shows a 2px line colour outline offset 2px. Disabled drops to 60% opacity with a progress cursor.
- **Text link:** line text colour, 600, with a Phosphor arrow that slides 4px right on hover while the label underlines (4px offset).
- **Quiet link button:** 14px / 20px at 600 in line text colour, 4px 8px, fills with raised enamel on hover.

### Chips
- **Style:** caption type, full pill, 2px 8px. Outline chip: 1px line teal border with teal text ("Cheaper brand allowed"). Solid chip: band fill with white text ("Cheapest").
- **State:** chips are status labels, not controls.

### Cards / Containers
- **Door:** the landing page's first-screen panels. 24px radius (16px on phones), surface fill, 1px rule border, Float shadow, 32px padding (16px on phones), a 24px / 32px title and a muted lede. Exactly two, side by side above 1023px, stacked below; they rise in with a 120ms stagger. No other surface uses this container.
- **Screen fragment:** the only card inside a section. A 16px rounded surface panel with a 1px rule border, 24px padding (16px on phones) and the Float shadow, showing a real piece of the product with real demo data beside each station. A flush variant holds divided lists (8px vertical, rows 12px 24px).
- **Demo card:** on the sign-in page, a 12px rounded button tinted 10% with the line colour over surface, 1px line colour border, 12px 16px padding; lifts 2px with the Float shadow on hover.
- **Border:** 1px hairline rule; rows inside a panel divide with the same rule.

### Inputs / Fields
- **Style:** 40px high, 8px 12px padding, 8px radius, surface fill, 1px rule border, body type. Label above in label type.
- **Hover:** border darkens to muted ink.
- **Focus:** border takes the line colour with a 4px Focus halo; no outline.
- **Error:** a message block above the form, 12px 16px, 8px radius, 1px alert red border on an 8% red tint, ink text, `role="alert"`.

### Navigation
- **Island:** a floating pill fixed 24px from the top, centred, 80% surface with a 16px blur, 1px rule border, Float shadow. Holds the roundel wordmark, quiet pill links (14px / 20px at 500, muted ink, raised fill and ink on hover): How it works, Find a doctor, and Sign in. No button: the two doors are the call to action, and doctors and chemists reach their sign-in from their line sections and the sign-in page's role switch.
- **Mobile:** under 860px the links hide and a 40px round burger appears; opening it fades in a full screen frosted sheet whose links (30px / 36px at 600) rise 48px into place with a 50ms stagger.

### Role switch (signature)
A three position segmented pill on the raised well: a band coloured slider glides between Patient, Doctor and Chemist over 700ms while the whole page's line colour changes with it. Tabs are label type in muted ink; the current one takes on band text.

### Line and station (signature)
The journey is a vertical 8px track with 28px roundels. As the visitor scrolls, the line fills with teal station by station and each screen fragment rises into place. On the map, the patient's home roundel sits inside dashed 1 and 3 km rings; chemist roundels pulse while asked, fill teal when they have it, fill marigold when partly, and their flush labels gain a 2px underline in the matching colour.

### Body guide (signature)
A front or back silhouette (viewBox 200 by 440, at most 200px wide) whose areas are the drawing: each is one path filled at 12% ink over surface with a 3px surface stroke separating it from its neighbours. Hover and keyboard focus tint an area 22% patient teal; the chosen area fills with the patient band and carries a white pulsing pin. A Front | Back pill on the raised well switches the view. Choosing an area opens a sheet below the figure (16px radius, ground fill, 1px rule): chip controls for symptoms and duration (full pill, 1px rule, band fill when pressed; warning signs have a dashed border), then the answer in the same sheet: a muted lead line, a 20px / 28px "See a …" title, the rationale, a block primary button and a quiet alternative. Warning signs replace the answer with an alert sheet in alert red on an 8% red tint, with red call buttons for 112 and 108. A plain list of areas as chips is always one quiet link away.

### Search box
A combobox inside a 12px rounded field (surface, 1px rule; teal border and 4px halo on focus) with a magnifier, the input, and a compact primary Search button. Suggestions open in a floating list (12px radius, Float shadow, 8px padding) grouped by 12px caption headings: "For what you described", "Specialities", "Doctors"; the active option takes the raised fill. Beneath it, six speciality shortcuts in a three column grid (two on phones): 44px minimum height, 12px radius, 1px rule, a teal Phosphor icon and a label; they lift 2px on hover.

### Pick up code
Six Geist Mono cells on raised enamel, 48 by 64px (36 by 48px on phones), 8px radius, drawn as a segment display: unlit segments at 12% ink, lit segments full ink.

## Do's and Don'ts

### Do:
- **Do** scope every rule and token under `.lm` and use it only on the landing and sign-in pages.
- **Do** set the active role with `data-role` and read colour through `--lm-line`, `--lm-band` and `--lm-line-text`.
- **Do** take type sizes only from 12/14/16/18/20/24/30/36/48/60px with their paired line heights.
- **Do** take spacing only from 2/4/8/12/16/24/32/40/48/64/80/96px.
- **Do** pad buttons 8px 12px with 16px semibold labels.
- **Do** animate with `cubic-bezier(0.32, 0.72, 0, 1)` at 700ms, and collapse all motion under `prefers-reduced-motion`.
- **Do** use Phosphor icons.
- **Do** write sentence case, plain copy with no hyphens.
- **Do** show real demo data in screen fragments; set prices, times, distances and codes in Geist Mono.

### Don't:
- **Don't** use background gradients, or gradient text anywhere but the hero heading.
- **Don't** give a surface more than one active role colour, or use marigold as text on enamel.
- **Don't** build structure from cards: the two doors and the screen fragments are the only card-like containers.
- **Don't** cast shadows from sections, bands or the ground.
- **Don't** use background blur outside the floating navigation and its sheet.
- **Don't** bring the workspace tokens from `styles.css` into these pages, or these tokens into the workspaces.
- **Don't** use circles for anything but stations and statuses.
