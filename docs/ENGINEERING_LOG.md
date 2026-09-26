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
| Testcontainers | Tests run against real PostgreSQL, same major version as production | H2: has none of the constraints the design depends on |

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

## 6. Login timing leak: account existence was detectable (PR #7)

**How it was found.** While writing the interview guide, each security claim
was checked against the code. `AuthService.login` claimed that a failed login
takes the same time whether or not the email exists. Measuring production
disproved it: over 8 interleaved attempts each, a known email averaged 0.72 s
and an unknown one 0.50 s. That ~220 ms gap is one cost-12 BCrypt hash, and it
lets anyone test which emails are registered.

**Cause.** For unknown emails, the code compared against a hand-typed
placeholder hash so that BCrypt would still run. The placeholder had 59
characters after `$2a$12$` where BCrypt needs exactly 53. Spring's
`BCryptPasswordEncoder` checks the full string against a pattern *before*
hashing and returns `false` at once on a mismatch, so no hashing happened.
(A first check with Python's `re.match` wrongly passed it: that matches a
prefix, while Java's `Matcher.matches()` requires the whole string.)

**Fix.** Generate the placeholder at startup with the application's own
encoder. It is then well-formed by construction and always has the same cost
factor as stored passwords, even if the cost is changed later.

**Verified by.** `LoginTimingTest` records the hash the encoder is asked to
check for an unknown email and asserts it is well-formed and of equal cost.
Timing itself is not asserted, since wall-clock tests are flaky. The test was
run against the old code and fails there, naming the malformed hash.

---

## 7. Audit trail (PR #8)

**The gap.** V5 created an `audit_log` table and a trigger rejecting UPDATE
and DELETE, and the README described an immutable audit trail. No code wrote
to the table.

**What is recorded.**

| Event | When | Transaction |
|---|---|---|
| `APPOINTMENT_BOOKED`, `APPOINTMENT_CANCELLED` | a change succeeds | the change's own |
| `APPOINTMENT_VIEWED` | a doctor or admin reads a patient's appointment | independent |
| `ACCESS_DENIED` | a row-level or role check refuses a request | independent |
| `LOGIN_SUCCEEDED`, `LOGIN_FAILED`, `LOGIN_REFUSED_DISABLED` | every login attempt | independent |

**The transaction choice is the design.** `AuditLog.recordChange` uses
`Propagation.MANDATORY`: it joins the caller's transaction, so a booking and
its audit row commit or roll back together. The log can never claim a booking
that did not happen. `recordIndependently` uses `REQUIRES_NEW`: a denial is
followed by an exception that rolls back the request's transaction, and an
audit row written inside it would vanish with it, losing exactly the event an
investigator needs. Independent writes are best-effort: if the audit insert
fails, the 403 still goes out rather than becoming a 500, and the failure is
logged at ERROR.

**A second gap, found while testing.** V5's trigger is `FOR EACH ROW`, and
PostgreSQL does not fire row-level triggers on `TRUNCATE`. One
`TRUNCATE audit_log` would have erased the whole trail. V6 adds a
statement-level `BEFORE TRUNCATE` trigger. V5 was not edited: applied
migrations are immutable, and editing one fails Flyway's checksum validation
on every existing database.

**Other decisions.**
- Plain JDBC, not a JPA entity: the table is insert-only, uses `inet` and
  `jsonb`, and is never loaded as an object graph.
- Explicit calls rather than an AOP aspect (V5's comment anticipated one): the
  outcome and the entity id are only known inside the method, and an explicit
  call is visible to whoever reads the code.
- Client IP behind the proxy comes from Tomcat's RemoteIpValve
  (`forward-headers-strategy: native`), which trusts `X-Forwarded-For` only
  from private-range proxies. The `framework` strategy would trust the header
  from anyone, letting a client write a fake address into the audit log.
- Failed logins keep the attempted email: that is what reveals credential
  stuffing. Both failure branches write the row, so the audit does not
  reintroduce the timing difference fixed in entry 6.
- `GET /api/v1/admin/audit` lets an admin search the trail.

**Verified by.** `AuditTrailTest`: a booking is recorded with its actor and
IP; a booking that loses the race leaves no `APPOINTMENT_BOOKED` row; a
denial is kept although its request is rolled back; a doctor's read is
recorded and the patient's own read is not; a failed login is recorded;
UPDATE, DELETE and TRUNCATE are all refused; only an admin can read the trail.

---

## 8. Pharmacy: dispensing a prescription (PR #9)

**The gap.** Stock logic and its concurrency test existed, but nothing could
call them, and nothing prevented a prescription being filled twice.

**Endpoints.** `POST /api/v1/pharmacy/prescriptions/{id}/dispense`,
`POST /api/v1/pharmacy/medicines/{id}/restock`, `GET /api/v1/pharmacy/stock/low`
(all admin, standing in for a pharmacist role), and a catalogue search for any
signed-in user. The patient portal now shows when each prescription was
dispensed.

**Three guarantees, all enforced by PostgreSQL.**

1. *At most once.* V7 adds `prescription_dispensations` with a unique
   constraint on `prescription_id`. The service inserts that row **before**
   touching stock. A concurrent second attempt blocks on the unique index
   until the first commits, then fails with `409 ALREADY_DISPENSED`, having
   moved no stock. This is the booking pattern reused: optimistic insert,
   constraint decides, constraint name mapped to a response.
2. *All or nothing.* Every medicine is decremented in one transaction. If the
   second medicine is short, the exception rolls back the first decrement and
   the dispensation row with it. The error names the medicine that is short.
3. *Never below zero.* Each decrement is the existing conditional UPDATE,
   backed by the CHECK constraint.

**Deadlock avoidance.** Stock rows are locked in ascending medicine-id order.
Two prescriptions sharing medicines, processed in opposite orders, would each
hold one row lock while waiting for the other's: a deadlock that PostgreSQL
resolves by killing one transaction. A single global lock order makes that
cycle impossible.

**Superseded prescriptions are refused** (`422 PRESCRIPTION_SUPERSEDED`):
filling the original would hand the patient instructions the doctor withdrew.

**Refactor.** The constraint-name lookup moved from `BookingService` into
`common/Constraints` now that two services use it.

**Verified by.** `DispensingTest`: every item decremented with a movement row
each; a second dispense refused with stock untouched; 16 threads released
together on one prescription produce exactly one success and stock taken
once; one short medicine leaves both untouched and records no dispensation;
superseded refused while its correction succeeds; patient and doctor get 403,
and the portal shows `dispensedAt` afterwards; restock is audited and clears
the low-stock flag.

---

## 9. Test on the database version production runs (PR #10)

Railway provisioned PostgreSQL 18; CI and `docker-compose` ran 16. Every
guarantee in this project is a PostgreSQL behaviour (partial indexes,
exclusion constraints, trigger semantics, error texts the tests match on), so
proving them on 16 said nothing certain about 18. Testcontainers and compose
now use `postgres:18-alpine`.

The 18 image moved its data directory to `/var/lib/postgresql/18/docker` and
refuses to start with a volume mounted at the old `/var/lib/postgresql/data`,
so the compose volume now mounts `/var/lib/postgresql`. A local volume
created by the 16 image cannot be opened by 18 and must be recreated.

Flyway logs that PostgreSQL 18 is newer than its tested versions. Migrations
apply cleanly in CI and production; the warning is expected until the Spring
Boot upgrade brings a newer Flyway.

**Verified by.** The full suite passing on PostgreSQL 18 in CI.

---

## Known gaps (tracked, not hidden)

- **No doctor workflow.** Nothing lets a doctor complete a visit or issue a
  prescription; portal history currently comes from seed data.
