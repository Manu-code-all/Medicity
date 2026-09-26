# Engineering log

A running record of how Medicity was built: what changed, why that design won
over the alternatives, what broke along the way, and how each change was
verified. Newest entries at the bottom. Every pull request adds an entry here.

Each entry links to the code and the test that proves its claims, because a
design decision is only as good as the evidence that it holds.

---

## 1. Rebuild the static template as a real platform (PR #1)

**Starting point.** A purchased Bootstrap HTML template ("Medcity"): static
pages, no backend, no data, nothing to reason about.

**Goal.** A hospital platform built around one hard requirement: *a consultation
slot admits exactly one patient, no matter how many people book it at the same
instant.*

**Stack and why.**

| Choice | Why | Rejected alternative |
|---|---|---|
| Java 21 + Spring Boot 3 | Mature transaction, security and validation story; the dominant enterprise stack in Indian product companies | Node/Express: weaker typing for a domain with many invariants |
| PostgreSQL | Partial unique indexes and GiST exclusion constraints make the core invariant enforceable *in the database* | MySQL: no exclusion constraints, no partial indexes |
| Flyway | Schema is versioned and reviewed like code; Hibernate only validates (`ddl-auto: validate`) | `ddl-auto: update`: silent, unreviewable schema drift |
| React + TypeScript + TanStack Query | Server state (caching, invalidation, refetch) handled by a library built for it | Hand-rolled `useEffect` fetching |
| Testcontainers | Tests run against real PostgreSQL 16 | H2: has none of the constraints the design depends on |

**The core design: let the database arbitrate.**

- `uq_active_appointment_per_slot`: a *partial* unique index on
  `appointments(slot_id) WHERE status <> 'CANCELLED'`. Two concurrent bookings
  of one slot: the second blocks on the index, then fails with a unique
  violation. A plain `UNIQUE(slot_id)` would also stop double booking but would
  permanently burn a slot after a cancellation.
  → `backend/src/main/resources/db/migration/V2__scheduling.sql`
- `no_overlapping_slots_per_doctor`: a GiST `EXCLUDE` on
  `tstzrange(starts_at, ends_at, '[)')` so a doctor can never have two
  overlapping slots. Half-open ranges let 10:00–10:30 and 10:30–11:00 coexist.
- The service inserts optimistically and translates the constraint violation
  into a `409 SLOT_ALREADY_BOOKED`, matching on the constraint *name* so an
  unrelated violation is not misreported.
  → `scheduling/BookingService.java`
- Why not check-then-insert? Two requests can both read "free" before either
  writes. Why not `synchronized`? It works on one JVM only and fails the moment
  a second instance is deployed. Why not `SELECT ... FOR UPDATE`? It works, but
  it is extra locking to maintain; the index already gives the guarantee.

**Pharmacy stock** is a count, not an identity, so uniqueness cannot help.
It uses `CHECK (quantity_on_hand >= 0)` plus an atomic
`UPDATE ... SET qty = qty - :n WHERE qty >= :n`; zero rows updated means
insufficient stock. → `pharmacy/MedicineStockRepository.java`, `pharmacy/StockLedger.java`, `V4__pharmacy.sql`

**Security.** JWT access and refresh tokens carry a `typ` claim, so a refresh
token cannot be replayed as an access token. BCrypt cost 12. Row-level
ownership checks on every appointment read, because every patient holds
`ROLE_PATIENT` and a role check alone permits IDOR. Default-deny routing.
→ `security/`, `scheduling/AppointmentController.java#requireAccess`

**Verified by.** `SlotBookingConcurrencyTest` (up to 64 threads released
simultaneously by a `CountDownLatch`; exactly one booking succeeds),
`StockLedgerConcurrencyTest`, `AppointmentAccessControlTest`.

**Bugs caught the first time tests ran on a real database:**

1. *Unauthenticated requests got 403, not 401.* With no form or basic login
   configured, Spring Security falls back to `Http403ForbiddenEntryPoint`.
   Fix: an explicit `RestAuthenticationEntryPoint`.
2. *`LazyInitializationException` in the doctor authorization path.* With
   `open-in-view: false`, walking `slot → doctor` after the transaction closed
   fails. Reading an id off a proxy works, which is why the patient path
   survived. Fix: a `JOIN FETCH` query (`findByIdWithDetails`).
3. *CI could not publish the JUnit report* ("Resource not accessible by
   integration"). Fix: grant the job `checks: write`.

---

## 2. Deploy: Railway (API + Postgres) and Vercel (web) (PR #2)

- `DatabaseUrlEnvironmentPostProcessor` converts the platform's
  `postgresql://user:pass@host/db` into a JDBC URL before the datasource is
  built, so the same image runs on Railway, Render or Heroku unchanged.
- `server.port: ${PORT:8080}`: PaaS hosts assign the port.
- CORS origins come from `MEDICITY_CORS_ORIGINS` rather than code.
- Multi-stage Dockerfile, non-root user, `MaxRAMPercentage=75` so the JVM
  respects the container limit; health check on `/actuator/health/readiness`.
- Demo data loads only under the `demo` profile (`db/seed` added to Flyway
  locations), so a real deployment never contains demo accounts by accident.

**Deployment issues and fixes.** Vercel failed in one second: the dashboard's
Root Directory pointed at a folder that did not exist; cleared it so
`vercel.json` at the repo root applies. Railway's builder could not detect the
app at the repo root; set the service root to `/backend`. `VITE_API_BASE_URL`
is inlined at *build* time, so changing it requires a redeploy, not a restart.

---

## 3. Production-only bug: doctor search returned 500 (PR #3)

**Symptom.** The very first request to the live API, `GET /api/v1/doctors`
with no filters, returned 500: `function lower(bytea) does not exist`.

**Cause.** With a filter omitted, the JPQL parameter is null. Hibernate cannot
infer a type from null, the driver sends it untyped, and PostgreSQL resolves it
as `bytea`. `lower(bytea)` does not exist.

**Why tests missed it.** No test called the endpoint with every filter null,
which is exactly the landing-page case.

**Fix.** Wrap each optional parameter in `cast(:p as String)`, which types it
regardless of value. Same fix applied to `MedicineRepository`, which had the
latent bug. **Verified by** `DoctorSearchTest`, including the all-null case.

---

## 4. Patient landing page and portal (PR #4)

**Goal.** A public landing page; after signing in, a portal holding the
patient's whole history.

**Backend.**
- Mapped the existing V3 `prescriptions` tables. Prescriptions are
  append-only: a correction is a new row with `supersedes_id` pointing at the
  original, and a unique index keeps the chain linear.
- New endpoints under `/api/v1/patients/me`: profile, summary, visits
  (`scope=upcoming|past`), prescriptions. **No route takes a patient id.** "Me"
  is resolved from the token, so there is no parameter to tamper with: the IDOR
  surface is removed rather than guarded.
- Upcoming (`BOOKED` and in the future) and past (everything else) are exact
  complements, so a visit still `BOOKED` after its time moves to history
  instead of vanishing.
- Only current prescriptions are returned (`NOT EXISTS` a newer one
  superseding it), so a patient never sees two conflicting instructions.
- The prescriptions query fetch-joins a collection, so it returns a `List`,
  not a page: Hibernate cannot apply SQL `LIMIT` to a collection fetch and
  would silently page in memory.
- Summary counts come from one `GROUP BY status` query, not one query per status.

**Frontend.** Landing page with live specialists (the section disappears
rather than breaking if the API is down). Portal with overview, visits,
prescriptions and profile. **The query cache is cleared on sign-in and
sign-out**: cached queries hold medical records, and on a shared computer the
next person must not see the previous patient's data.

**Verified by.** `PatientPortalTest`: a second patient with their own history
proves isolation; the upcoming/past partition and ordering; a superseded
prescription is hidden; anonymous gets 401 and a doctor gets 403.

---

## 5. Client errors returned 500 (PR #5)

**Symptom.** Any unknown URL on the live API returned 500, and so did a wrong
HTTP method, malformed JSON and a malformed UUID. Each was logged as an incident.

**Cause.** Spring MVC signals these client errors as exceptions
(`NoResourceFoundException`, `HttpRequestMethodNotSupportedException`, ...).
The `@ExceptionHandler(Exception.class)` catch-all treated them as crashes.

**Fix.** The catch-all first checks for Spring's `ErrorResponse` interface,
which these exceptions implement and which already carries the correct status
and headers (the `Allow` header on a 405 is kept). It is checked inside the
catch-all because `@ExceptionHandler` cannot target an interface. Unreadable
bodies and type mismatches get explicit 400 handlers with fixed messages, so
parser internals never reach the client. Also fixed: error `type` URIs dropped
their `/errors/` segment because `URI.resolve` replaces the last path segment
unless the base ends in `/`.

**Verified by.** `ErrorContractTest`, and on production: 404, 405 with
`Allow: GET`, 400, 400.

---

## Known gaps (tracked, not hidden)

- **Audit trail is designed but not yet written to.** The `audit_log` table
  and its immutability trigger exist (V5), but no code records events yet.
- **Pharmacy has no HTTP API.** Stock logic and its concurrency test exist;
  dispensing is not exposed.
- **No doctor workflow.** Nothing lets a doctor complete a visit or issue a
  prescription; portal history currently comes from seed data.
- CI tests PostgreSQL 16; production runs 18.
