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

The suite runs on every push against real PostgreSQL 16 via Testcontainers:

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
        SVC["Domain services<br/>BookingService · StockLedger"]
        REPO["Spring Data JPA"]
    end

    subgraph data [Data]
        PG[("PostgreSQL 16<br/>constraints as invariants")]
        RD[("Redis<br/>cache · rate limiting")]
    end

    WEB -->|"Bearer JWT"| SEC
    SEC --> CTL --> SVC --> REPO --> PG
    SVC --> RD

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
| `appointments_cancel_consistency` — `CHECK` | V2 | A cancelled row with no cancellation timestamp |
| `audit_log` immutability — `BEFORE UPDATE OR DELETE` trigger | V5 | An attacker erasing their own audit trail |

The `EXCLUDE` constraint uses a half-open range `'[)'`, so 10:00–10:30 and
10:30–11:00 do *not* conflict — exactly what back-to-back consultations need.

### Security

- **JWT with separated token types.** Access and refresh tokens carry a `typ`
  claim and are verified against it, so a stolen refresh token cannot be replayed
  as a bearer credential. Tested in `AppointmentAccessControlTest`.
- **Row-level authorization.** Role checks alone are insufficient: every patient
  holds `ROLE_PATIENT`, so `@PreAuthorize("hasRole('PATIENT')")` would let
  patient A read patient B's record by guessing an id. Access is decided per row,
  by ownership.
- **Uniform error responses.** A forbidden resource and a non-existent one return
  identical bodies, so id probing cannot confirm what exists.
- **No user enumeration.** Login hashes a dummy value when the account is missing,
  keeping the timing profile of both branches comparable.
- **Default-deny routing.** Adding an endpoint cannot accidentally expose it.
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
| `patient@medicity.demo` | PATIENT |
| `dr.rao@medicity.demo` | DOCTOR |
| `admin@medicity.demo` | ADMIN |

Password for all three: `demo-password-2026`

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
docker compose up -d db redis
cd backend && mvn spring-boot:run
```

### Tests

```bash
cd backend && mvn verify
```

The suite uses **Testcontainers with real PostgreSQL 16**, never H2. Partial
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
| `POST` | `/api/v1/auth/refresh` | — | Rotate tokens |
| `GET` | `/api/v1/doctors` | — | Search doctors |
| `GET` | `/api/v1/doctors/{id}/slots` | — | Available slots |
| `POST` | `/api/v1/appointments` | PATIENT | **Book a slot** |
| `POST` | `/api/v1/appointments/{id}/cancel` | owner | Cancel, releasing the slot |
| `GET` | `/api/v1/appointments/mine` | PATIENT / DOCTOR | Own appointments |

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
  src/main/resources/db/migration/   V1–V5, the real specification
  src/test/java/com/medicity/
    scheduling/SlotBookingConcurrencyTest.java   ← the proof
    security/AppointmentAccessControlTest.java   ← IDOR coverage
frontend/         React 18 + TypeScript + Vite
legacy-template/  the original static HTML theme, kept for reference only
```

`legacy-template/` is a third-party commercial theme that predates this rebuild.
It is retained only so the original pages remain viewable; none of it is part of
the running application.

---

## Deployment

`vercel.json` builds the React app from `frontend/` and serves it as an SPA —
every unmatched path falls through to `index.html`, so a refresh on
`/appointments` does not 404. Hashed assets under `/assets/` are served
`immutable`, since their filenames are content-addressed.

Vercel hosts the frontend only; the API needs a container runtime and a
database. Deploy `backend/` to Railway, Render or Fly.io with a Postgres
instance attached, then set on the Vercel project:

| Variable | Value |
|---|---|
| `VITE_API_BASE_URL` | the deployed API origin, e.g. `https://medicity-api.up.railway.app` |

And on the API:

| Variable | Notes |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | from the managed Postgres instance |
| `JWT_SECRET` | 32+ bytes; the app refuses to start below that |
| `SPRING_PROFILES_ACTIVE` | `demo` to seed clickable data |

The API's CORS allow-list in `SecurityConfig` is set to localhost origins, so
add the deployed frontend origin there before going live.

## Roadmap

- [ ] Redis-backed rate limiting on auth endpoints
- [ ] Notification service (email/SMS) on booking and cancellation
- [ ] Prometheus metrics + Grafana dashboard
- [ ] Doctor availability rules engine (recurring weekly templates)
- [ ] k6 load test establishing booking throughput under contention
