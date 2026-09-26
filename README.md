# Medicity

A hospital management platform — appointment scheduling, clinical records, and
pharmacy inventory — built around one hard requirement:

> **A consultation slot admits exactly one patient, no matter how many people
> tap "Book" at the same instant.**

That sentence is the reason this codebase looks the way it does. Everything
below follows from it.

[![CI](https://github.com/Manu-code-all/Medicity/actions/workflows/ci.yml/badge.svg)](https://github.com/Manu-code-all/Medicity/actions/workflows/ci.yml)

---

## The problem worth solving

Appointment booking looks trivial until two patients want the same slot. The
implementation almost everyone writes first is:

```java
if (slotIsFree(slotId)) {       // 1. check
    createAppointment(slotId);  // 2. act
}
```

This is a **time-of-check to time-of-use** bug. Two requests can both execute
step 1 before either reaches step 2. Both observe "free". Both insert. Two
people show up at 10:00 for the same doctor.

It passes every single-threaded test you will ever write for it.

### How Medicity handles it

The invariant lives in the database, not in application code:

```sql
-- migration V2__scheduling.sql
CREATE UNIQUE INDEX uq_active_appointment_per_slot
    ON appointments (slot_id)
    WHERE status <> 'CANCELLED';
```

A **partial** unique index, not a plain one. The distinction matters: a plain
`UNIQUE(slot_id)` would burn the slot permanently once an appointment was
cancelled. Scoping uniqueness to non-cancelled rows means cancellation frees the
slot for reuse while still permitting exactly one active holder at any instant.

The service layer then attempts the insert optimistically and translates the
constraint violation into a domain error:

```java
try {
    return appointmentRepository.saveAndFlush(appointment);
} catch (DataIntegrityViolationException e) {
    if (UQ_ACTIVE_APPOINTMENT_PER_SLOT.equalsIgnoreCase(constraintNameOf(e))) {
        throw new ConflictException("SLOT_ALREADY_BOOKED", "...");
    }
    throw e;
}
```

`saveAndFlush`, not `save` — flushing forces the INSERT to execute inside the
`try` block. With a plain `save()`, Hibernate defers the statement until
transaction commit, which happens *after* the method returns, so the `catch`
never fires and the user gets an opaque 500 instead of a clean 409.

**Why not pessimistic locking?** `SELECT ... FOR UPDATE` works, but serialises
every reader of that row and holds the lock for the whole transaction. The
optimistic path never blocks readers and costs nothing in the uncontended case,
which is the overwhelming majority of bookings.

**Why not `SERIALIZABLE`?** It would also be correct, but it resolves the
conflict by aborting a transaction — the same outcome the constraint produces,
reached more expensively, while imposing retry semantics on every unrelated
transaction in the application.

### The proof

`SlotBookingConcurrencyTest` releases up to 64 threads simultaneously at a single
slot and asserts on what the database actually contains afterwards:

```java
assertThat(succeeded.get()).isEqualTo(1);
assertThat(rejectedAsConflict.get()).isEqualTo(contenders - 1);
assertThat(appointmentRepository.findAll()).hasSize(1);
```

A `CountDownLatch` parks every thread until all of them are alive, then releases
them together. Without it, thread pool scheduling staggers the starts enough that
the race often never occurs and the test passes vacuously — proving nothing.

The suite runs on every push against real PostgreSQL 18 via Testcontainers:

```
SlotBookingConcurrencyTest   Tests run: 6, Failures: 0, Errors: 0
StockLedgerConcurrencyTest   Tests run: 3, Failures: 0, Errors: 0
AppointmentAccessControlTest Tests run: 7, Failures: 0, Errors: 0
```

Two bugs were caught the first time these ran against a real database, both
recorded in the commit history: an unauthenticated request answering `403`
instead of `401` (Spring Security silently defaults to
`Http403ForbiddenEntryPoint` when no interactive login mechanism is configured),
and a `LazyInitializationException` where the authorization check walked
`slot → doctor` outside a transaction with `open-in-view` disabled. Neither is
reachable from a unit test with a mocked repository.

The same reasoning is applied a second time, with a different answer, to pharmacy
stock. Uniqueness cannot help when the contended resource is a *count* rather than
an identity, so that path uses a `CHECK (quantity_on_hand >= 0)` constraint plus
an atomic `UPDATE ... SET qty = qty - :n WHERE qty >= :n`.

---

## Architecture

```mermaid
flowchart TB
    subgraph client [Client]
        WEB["React 18 + TypeScript<br/>Vite · TanStack Query"]
    end

    subgraph api [Spring Boot 3 · Java 21]
        SEC["Security filter chain<br/>JWT · BCrypt · RBAC"]
        CTL["REST controllers<br/>/api/v1"]
        SVC["Domain services<br/>Booking · Visits · Prescribing · Dispensing"]
        AUD["AuditLog<br/>append-only"]
        REPO["Spring Data JPA"]
    end

    subgraph data [Data]
        PG[("PostgreSQL 18<br/>constraints as invariants")]
    end

    WEB -->|"Bearer JWT"| SEC
    SEC --> CTL --> SVC --> REPO --> PG
    SVC --> AUD --> PG

    style PG fill:#1a5f3f,color:#fff
    style SEC fill:#7a3b1f,color:#fff
```

### Where the invariants live

The schema is the specification. Each of these makes an invalid state
*unrepresentable*, rather than merely unlikely:

| Constraint | Migration | Prevents |
|---|---|---|
| `uq_active_appointment_per_slot` — partial unique index | V2 | Two patients holding one slot |
| `no_overlapping_slots_per_doctor` — GiST `EXCLUDE` on `tstzrange` | V2 | A doctor double-booked across two overlapping slots |
| `uq_patient_active_at_time` — partial unique index | V2 | One patient booked with two doctors at the same instant |
| `stock_never_negative` — `CHECK` | V4 | Overselling medicine under concurrent dispensing |
| `uq_dispensation_prescription` — `UNIQUE` | V7 | One prescription dispensed twice |
| `uq_presc_original_per_appointment` — partial unique index | V8 | Two original prescriptions for one visit |
| `appointments_cancel_consistency` — `CHECK` | V2 | A cancelled row with no cancellation timestamp |
| `uq_refresh_one_live_per_family` — partial unique index | V9 | A session forking into two live refresh tokens |
| `audit_log` immutability — `BEFORE UPDATE OR DELETE` row trigger + `BEFORE TRUNCATE` statement trigger | V5, V6 | An attacker erasing their own audit trail |

The `EXCLUDE` constraint uses a half-open range `'[)'`, so 10:00–10:30 and
10:30–11:00 do *not* conflict — exactly what back-to-back consultations need.

### Security

- **Short-lived access tokens, single-use refresh tokens.** Access tokens are
  15-minute JWTs. Refresh tokens are opaque, stored only as a SHA-256 hash, and
  work once; presenting a spent one is treated as theft and ends that whole
  session. Signing out revokes it server-side. Tested in `SessionSecurityTest`.
- **Login limit.** 5 failed logins per email in 15 minutes, then `429` with
  `Retry-After`. Counted from the audit log, so it holds across instances and
  restarts; unknown emails are limited identically, so it reveals nothing.
- **Safe retries.** Booking accepts an `Idempotency-Key`, so a retry after a
  dropped response returns the original booking instead of an error.
- **Row-level authorization.** Role checks alone are insufficient: every patient
  holds `ROLE_PATIENT`, so `@PreAuthorize("hasRole('PATIENT')")` would let
  patient A read patient B's record by guessing an id. Access is decided per row,
  by ownership.
- **Uniform error responses.** A forbidden resource and a non-existent one return
  identical bodies, so id probing cannot confirm what exists.
- **No user enumeration.** Login hashes a dummy value when the account is missing,
  keeping the timing profile of both branches comparable.
- **Default-deny routing.** Adding an endpoint cannot accidentally expose it.
- **No cross-account cache bleed.** The web client clears its query cache on
  sign-in and sign-out, so on a shared computer the next person to sign in never
  sees the previous patient's records, even for a frame.
- **BCrypt cost 12** (~250 ms/hash) and a 12-character minimum password with no
  composition rules, following NIST SP 800-63B.

---

## Running it

### Prerequisites

| | Version | Notes |
|---|---|---|
| JDK | 21+ | Spring Boot 3 requires 17 minimum; this targets 21 |
| Maven | 3.9+ | Bundled with IntelliJ; otherwise `brew install maven` / `choco install maven` |
| Docker | any recent | Required — Testcontainers starts Postgres for the test suite |
| Node | 20+ | Frontend only |

### Everything at once

```bash
docker compose up --build
```

To start with a populated database — three doctors, two weeks of open slots,
a patient account and a medicine catalogue — run with the `demo` profile:

```bash
SPRING_PROFILES_ACTIVE=demo docker compose up --build
```

| Demo account | Role |
|---|---|
| `patient@medicity.demo` | PATIENT — Meera, with a full visit and prescription history |
| `arjun@medicity.demo`, `kavya@medicity.demo` | PATIENT — on Dr. Rao's calendar today |
| `dr.rao@medicity.demo` | DOCTOR — one visit waiting to be closed, one later today |
| `dr.iyer@medicity.demo` | DOCTOR — useful for checking that doctors cannot reach each other's patients |
| `admin@medicity.demo` | ADMIN — pharmacy and audit trail |

Password for all of them: `demo-password-2026`

The seed lives in `db/seed/`, which is added to the Flyway path *only* by the
`demo` profile — a deployed environment has no path by which these accounts
could be created.

| Service | URL |
|---|---|
| Web app | http://localhost:5173 |
| API | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Health | http://localhost:8080/actuator/health |

### Backend alone

```bash
docker compose up -d db
cd backend && mvn spring-boot:run
```

### Tests

```bash
cd backend && mvn verify
```

The suite uses **Testcontainers with real PostgreSQL 18**, never H2. Partial
unique indexes, GiST exclusion constraints and `tstzrange` either do not exist in
H2 or behave differently there — a suite that passed on H2 would tell you nothing
about production, which is the entire point of these tests.

---

## API

Full interactive reference at `/swagger-ui.html`. Core endpoints:

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | — | Register a patient |
| `POST` | `/api/v1/auth/login` | — | Obtain a token pair |
| `POST` | `/api/v1/auth/refresh` | — | Rotate: spend a refresh token for a new pair (reuse ends the session) |
| `POST` | `/api/v1/auth/logout` | — | End the session the refresh token belongs to |
| `GET` | `/api/v1/doctors` | — | Search doctors |
| `GET` | `/api/v1/doctors/{id}/slots` | — | Available slots |
| `POST` | `/api/v1/appointments` | PATIENT | **Book a slot** (optional `Idempotency-Key`) |
| `POST` | `/api/v1/appointments/{id}/cancel` | owner | Cancel, releasing the slot |
| `GET` | `/api/v1/appointments/mine` | PATIENT / DOCTOR | Own appointments |
| `GET` | `/api/v1/patients/me` | PATIENT | Portal: profile |
| `GET` | `/api/v1/patients/me/summary` | PATIENT | Portal: totals and next visit |
| `GET` | `/api/v1/patients/me/appointments?scope=upcoming\|past` | PATIENT | Portal: visit history |
| `GET` | `/api/v1/patients/me/prescriptions` | PATIENT | Portal: current prescriptions |
| `GET` | `/api/v1/doctors/me/visits?from&to` | DOCTOR | Workspace: own schedule (max 31 days) |
| `GET` | `/api/v1/doctors/me/visits/{id}` | own doctor | Visit detail with current prescription |
| `POST` | `/api/v1/doctors/me/visits/{id}/complete` | own doctor | Close a visit as seen |
| `POST` | `/api/v1/doctors/me/visits/{id}/no-show` | own doctor | Close a visit as missed |
| `POST` | `/api/v1/doctors/me/visits/{id}/prescriptions` | own doctor | Issue the visit's prescription |
| `POST` | `/api/v1/doctors/me/prescriptions/{id}/corrections` | author | Correct (supersede) a prescription |
| `GET` | `/api/v1/doctors/me/patients/{id}/history` | treating doctor | A patient's history (audited) |
| `GET` | `/api/v1/pharmacy/medicines` | signed in | Medicine catalogue |
| `GET` | `/api/v1/pharmacy/stock/low` | ADMIN | Medicines below reorder level |
| `POST` | `/api/v1/pharmacy/medicines/{id}/restock` | ADMIN | Add stock |
| `POST` | `/api/v1/pharmacy/prescriptions/{id}/dispense` | ADMIN | Dispense, decrementing stock atomically |
| `GET` | `/api/v1/admin/audit` | ADMIN | Search the audit trail |

The portal routes take **no patient id at all**. "Me" is resolved from the token,
so there is no parameter a caller could alter to reach another patient's history:
the IDOR surface is removed rather than guarded. `PatientPortalTest` gives a
second patient a history of their own and asserts none of it leaks.

Errors follow **RFC 9457** `application/problem+json` and carry a stable
machine-readable `code`, so clients branch on the code rather than on prose:

```json
{
  "type": "https://medicity.dev/errors/slot-already-booked",
  "title": "Conflict",
  "status": 409,
  "detail": "This slot was just booked by someone else. Please choose another time.",
  "code": "SLOT_ALREADY_BOOKED",
  "path": "/api/v1/appointments"
}
```

Registration always creates a `PATIENT`. Doctor and admin accounts are
provisioned by an administrator — taking the role from the request body would let
anyone mint themselves an admin account.

---

## Design decisions worth defending

**Flyway owns the schema; Hibernate is set to `validate`.** `ddl-auto: update`
cannot express a partial index, a GiST exclusion constraint, or a trigger — the
three things this system's correctness depends on. It also silently diverges
between environments.

**`open-in-view: false`.** The default leaves a database connection held open for
the entire request including view rendering, and turns a forgotten `JOIN FETCH`
into an N+1 that only appears under load. DTOs are assembled inside the
transaction instead.

**`Clock` is injected, never `Instant.now()`.** Booking lead times, cancellation
windows and token expiry are all time-dependent rules. With an injected clock,
tests assert boundary behaviour deterministically instead of sleeping.

**Prescriptions are append-only.** A correction creates a new row that supersedes
the old one, which is how clinical systems handle medico-legal traceability — and
what makes the audit log meaningful.

**Audit rows are immutable at the database level.** A trigger rejects `UPDATE` and
`DELETE` outright, so an attacker holding the application's own credentials still
cannot quietly erase their tracks.

---

## Project layout

```
backend/
  src/main/java/com/medicity/
    common/       error contract, base entity
    security/     JWT, filter chain, auth
    user/         authentication principal
    doctor/  patient/
    scheduling/   BookingService  ← the interesting part
    clinical/     prescriptions
    pharmacy/     catalogue, stock ledger
    audit/        append-only audit trail
  src/main/resources/db/migration/   V1–V11, the real specification
  src/test/java/com/medicity/
    scheduling/SlotBookingConcurrencyTest.java   ← the proof
    security/AppointmentAccessControlTest.java   ← IDOR coverage
frontend/         React 18 + TypeScript + Vite
docs/ENGINEERING_LOG.md   every change, why it was made, and how it was verified
```

---

## Deployment

`vercel.json` builds the React app from `frontend/` and serves it as an SPA —
every unmatched path falls through to `index.html`, so a refresh on
`/appointments` does not 404. Hashed assets under `/assets/` are served
`immutable`, since their filenames are content-addressed.

Vercel hosts the frontend only; the API needs a container runtime and a
database.

### API on Railway

`backend/railway.json` selects the Dockerfile builder and points the health
check at `/actuator/health/readiness`, so a container that starts but cannot
reach its database is never routed traffic.

1. **New Project → Deploy from GitHub repo**, select this repository.
2. In the service's **Settings → Root Directory**, set `backend`. Railway then
   picks up `railway.json` and the Dockerfile, and uses `backend/` as the build
   context — which the Dockerfile's `COPY pom.xml .` requires.
3. **+ New → Database → PostgreSQL** in the same project.
4. Set the service variables:

| Variable | Value |
|---|---|
| `DATABASE_URL` | `${{Postgres.DATABASE_URL}}` — a Railway reference, not a literal |
| `JWT_SECRET` | 32+ bytes of random; the app refuses to start below that |
| `MEDICITY_CORS_ORIGINS` | your Vercel origin, e.g. `https://medicity.vercel.app` |
| `SPRING_PROFILES_ACTIVE` | `demo`, to seed clickable data |

Generate the secret with:

```bash
openssl rand -base64 48
```

5. **Settings → Networking → Generate Domain** to get a public URL.

`PORT` is injected by Railway and already read by `server.port`. `DATABASE_URL`
arrives as `postgresql://user:pass@host:5432/db`, which is *not* a JDBC URL —
`DatabaseUrlEnvironmentPostProcessor` converts it at startup, so the same image
also runs unmodified on Render, Fly and Heroku. Handing the raw value to Spring
otherwise fails with `Driver claims to not accept jdbcUrl`.

Railway's Postgres permits `CREATE EXTENSION pgcrypto` and `btree_gist`, which
migrations V1 and V2 require. A provider that blocks extension creation will
fail Flyway at startup and the app will not boot.

Give the service **512 MB minimum** — a JVM will not start reliably below that.

### Frontend on Vercel

| Variable | Value |
|---|---|
| `VITE_API_BASE_URL` | the Railway domain, e.g. `https://medicity-api.up.railway.app` |

Redeploy after setting it: Vite inlines `VITE_*` variables at build time, so
changing one has no effect until the app is rebuilt.

Leave **Root Directory empty** in the Vercel project. Pointing it at a
subdirectory means `vercel.json` is never read.

## Patient experience

- **Landing page** (`/`) — what Medicity offers, live specialists pulled from the
  API, and a preview of the portal. Degrades gracefully: if the API is down, the
  page still renders without the doctors section.
- **Patient portal** (`/portal`) — where a patient lands after signing in:
  - *Overview*: the next visit with a countdown, totals, recent visits and the
    latest prescription.
  - *Visits*: upcoming (soonest first) and history (newest first). The two lists
    are exact complements, so a visit whose time passed while still `BOOKED`
    moves to history instead of vanishing. Upcoming visits can be cancelled.
  - *Prescriptions*: dose, frequency and duration per medicine. Prescriptions are
    append-only; a correction supersedes the original, and only the current
    version is shown, so a patient never sees two conflicting sets of instructions.
  - *Profile*: personal and contact details.

The demo patient (`patient@medicity.demo` / `demo-password-2026`) is seeded with
a realistic history: completed visits with prescriptions, a cancellation, a
missed visit, a corrected prescription and an upcoming appointment.

## Doctor experience

- **Schedule** (`/doctor`) — the day's visits in the doctor's own time zone, with
  visits that have started but are still open flagged as *waiting to be closed*.
- **Visit** — close it as seen or as a no-show (only once it has started, and only
  from `BOOKED`), then write the prescription. A correction supersedes the
  original instead of editing it. If the patient cancels at the same moment the
  doctor closes the visit, optimistic locking lets exactly one win and the other
  gets a clear `409`.
- **Patient history** — available only to doctors who have treated that patient;
  every view, and every refused attempt, is written to the audit trail.

Try it as `dr.rao@medicity.demo` / `demo-password-2026`.

---

## Roadmap

- [x] Patient portal
- [x] Audit trail, pharmacy dispensing
- [x] Doctor workspace: close a visit and issue or correct a prescription
- [x] Refresh-token rotation with reuse detection
- [x] Login rate limiting
- [x] Idempotency keys on booking
- [ ] Editable patient profile
- [ ] Notification service (email/SMS) on booking and cancellation
- [x] Prometheus metrics (`/actuator/prometheus`, ADMIN only)
- [ ] Grafana dashboard
- [ ] Doctor availability rules engine (recurring weekly templates)
- [ ] k6 load test establishing booking throughput under contention
