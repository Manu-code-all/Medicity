# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

- **Patients in Indian cities**, often managing care for parents and children
  as well as themselves. They book a specialist, keep every visit and
  prescription in one place, and then need the medicines: today that means
  phoning or walking between chemists.
- **Doctors** at a multi-speciality clinic: a day's schedule, closing visits,
  writing prescriptions (typed, or a photo of a handwritten slip).
- **Neighbourhood chemists**: answer "do you have these medicines?" from
  nearby patients, hold stock aside, hand over against a code.
- **Evaluators** (recruiters, engineers) trying the public demo. They are a
  confirmed secondary audience: the landing page is patient-first but must
  give them a one-click way in.

## Product Purpose

One account from booking to having the medicines in hand: book a slot that
cannot be double-booked, keep visits and prescriptions current, then send the
doctor's prescription to every verified chemist within 1, 3 or 5 km, compare
their answers, reserve, and collect with a six-digit code.

## Positioning

The chemist step. Booking apps stop at the prescription and pharmacy apps sell
from their own warehouse; Medicity asks the independent chemists near the
patient, using the prescription the doctor issued in the same system, and
shows who has it, the price, the distance, and cheaper brands the doctor
allowed.

## Operating Context

- Public demo at medicity-coral.vercel.app with shared demo accounts
  (patient@, dr.rao@, chemist@medicity.demo; one public password) that reset
  nightly.
- Roles: PATIENT (portal), DOCTOR (workspace), CHEMIST (store), ADMIN (verifies
  store licences).
- Sign-in has one URL per role: /login (patient), /login/doctor,
  /login/chemist, each with a one-click demo sign-in.

## Capabilities and Constraints

- Booking with live slots; family members under one sign-in; refill
  reminders; how-to-take instructions in six Indian languages, shareable on
  WhatsApp; prescription photos read into a draft the doctor confirms (needs an
  API key to be switched on).
- Free for doctors and chemists; chemists need no stock system.
- Stores are verified by a person before they receive prescriptions.
- Frontend: React 18 + TypeScript + Vite, plain CSS with tokens in
  `frontend/src/styles.css` (light and dark). The portal, doctor and store
  workspaces share the same tokens and line colours (`workspace.css`).

## Brand Commitments

- Name: Medicity. Plain, direct voice; sentence case; no hype.
- Take ideas from Practo (booking clarity), Apollo 24|7 (one joined-up
  journey), Mayo Clinic (calm trust) and Tata 1mg (a concrete medicine card),
  without their names, logos, colours or layouts.
- Avoid discount banners, pop-ups, carousels and article-site clutter.

## Evidence on Hand

- Real demo data: doctors (e.g. Dr. Suresh Iyer, Neurology), patients (Meera
  Nair and her mother Lalitha), five demo chemists in Indiranagar, Bengaluru
  (Sri Sai Medicals, Nightingale Pharmacy, …), real medicine names and prices
  from the seed.
- No real users, testimonials, ratings, press or usage numbers exist. Do not
  invent any.

## Product Principles

1. Show the mechanism, not adjectives: a real comparison beats a claim.
2. One next step per screen.
3. Trust is earned by clarity and honesty (what is a demo, what is verified).
4. The patient's family is part of the product, not an afterthought.
