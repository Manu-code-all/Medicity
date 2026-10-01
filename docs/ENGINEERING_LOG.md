# Engineering log

A running record of how Medicity was built: what changed, why that design won
over the alternatives, what broke along the way, and how each change was
verified. Newest entries at the bottom. Every pull request adds an entry here.

Each entry links to the code and the test that proves its claims, because a
design decision is only as good as the evidence that it holds.

---

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

## 10. Doctor workspace (PR #13)

**Goal.** Close the loop: a doctor sees their day, records what happened at
each visit, prescribes, and corrects prescriptions. Patient history now comes
from real use rather than seed data.

**Endpoints** under `/api/v1/doctors/me`: `visits?from&to`, `visits/{id}`,
`visits/{id}/complete`, `visits/{id}/no-show`, `visits/{id}/prescriptions`,
`prescriptions/{id}/corrections`, `patients/{id}/history`. As in the portal,
no route takes a doctor id.

**A security rule that depended on order.** `SecurityConfig` made every
`GET /api/v1/doctors/**` public, for the doctor directory. The new routes live
under that prefix, so `GET /doctors/me/visits` would have been reachable
anonymously at the filter level. Spring Security checks matchers in order and
the first match wins, so a rule for `/doctors/me/**` now sits **above** the
public one. `workspaceIsNotPublic` asserts 401 for anonymous and 403 for a
patient, and that the directory is still public.

**Rules for closing a visit.** Only from `BOOKED`, only by the doctor whose
calendar it is on, and only after its start time.

**Three races, three mechanisms.**

| Race | Guard | Why this one |
|---|---|---|
| Two tabs issue the first prescription for a visit | V8 partial unique index: one original (`supersedes_id IS NULL`) per appointment | An insert race: a constraint decides |
| Two corrections of the same prescription | `uq_presc_supersedes` from V3 | Same, keeps the chain linear |
| Patient cancels while the doctor completes | `@Version` optimistic locking, answered as `409 CONCURRENT_UPDATE` | Read-decide-write on **one existing row**; no insert to constrain |

The third is the first place in the project where `@Version` is the actual
guard. Both requests read version *n*; the second `UPDATE ... WHERE version = n`
matches no row. `BookingService.cancel` now flushes inside the method so the
failure surfaces there and maps to a clean 409, rather than escaping at commit.

**"Today" is the client's.** The schedule endpoint takes two instants. The
browser computes local midnight to midnight, so the server never guesses the
doctor's time zone.

**Patient history is scoped and audited.** A doctor may read the full history
(across all doctors) of a patient who has been on their own calendar, and no
one else's. Every read writes `PATIENT_HISTORY_VIEWED`; a refusal writes
`ACCESS_DENIED`.

**Also.** Cancelling a missed visit is now refused. A site-wide link colour
was added: plain links rendered in the browser's default blue, unreadable on
the dark theme. Demo data gains two patients and two visits today for
`dr.rao@medicity.demo`, one already started.

**Verified by.** `DoctorWorkspaceTest` (10 tests): access rules; schedule
window and ownership; complete-before-start refused, double close refused;
another doctor refused and audited; 10 rounds of cancel-versus-complete each
producing exactly one winner; prescribing needs a completed visit and a second
original is refused; invalid and duplicate items rejected; 8 simultaneous
issues produce one prescription; a correction replaces the original for the
patient and cannot be repeated; history scoped and audited. The UI was
exercised against a mock API: sign in as doctor, open a visit, complete,
prescribe, open a correction.

**Known limitation.** A prescription can be corrected after it was dispensed.
The pharmacy will refuse the superseded original, but the patient may already
hold its medicines; a real system would notify the pharmacy. Not yet built.

---

## 11. README says only what the code does (PR #14)

A review of the README before starting security work found it claiming more
than the code does, which is the fastest way to lose an interviewer's trust:

- `/auth/refresh` was described as "Rotate tokens". It issues a new pair, but
  the old refresh token stays valid and replay is not detected. Relabelled;
  real rotation is the next piece of work.
- The architecture diagram showed Redis for caching and rate limiting. Nothing
  uses Redis (the unused starter was removed earlier because it broke the
  health check), and `docker compose up -d db redis` named a service that does
  not exist. Both removed.
- The API table, constraint table and demo accounts stopped at the patient
  portal, hiding the doctor workspace, pharmacy, audit trail and V6–V8.

The landing page now names the doctor demo account alongside the patient one,
and the 6.4 MB third-party HTML theme (`legacy-template/`) is deleted: none of
it ran, and it remains in git history.

**Verified by.** `npm run lint` and `npm run build`; a grep of the README for
Redis, rotation and legacy references.

---

## 12. Security hardening: sessions, login limits, safe retries (PR #15)

Three weaknesses, each a question an interviewer asks about any JWT app.

### Refresh tokens: single-use, with theft detection

**Before.** The refresh token was a signed JWT the server kept no record of.
It worked for its whole 7 days however often it was used, signing out only
deleted it from the browser, and a stolen copy would never be noticed.

**Now.** A refresh token is 32 random bytes; the database (V9) stores only its
SHA-256. SHA-256 rather than BCrypt because the value has 256 bits of
entropy, so there is nothing to brute-force, and finding the row needs a
deterministic hash. Each sign-in starts a **family**. Using a token marks it
used and issues its successor in the same family.

**Reuse detection.** A used token presented again means two parties hold it.
The server cannot tell the user from the thief, so it revokes the whole family
and both must sign in again; the event is audited as `REFRESH_TOKEN_REUSED`.
Other sign-ins (another device) are untouched. This is the scheme described in
the OAuth 2.0 Security Best Current Practice.

**Races.** The claim is one statement:
`UPDATE ... SET used_at = now WHERE token_hash = ? AND used_at IS NULL AND revoked_at IS NULL AND expires_at > now RETURNING ...`.
A second request with the same token waits on the first's row lock, then
re-checks the `WHERE`, matches nothing, and is treated as reuse. A partial
unique index allows at most one live token per family, so a family can never
fork.

**A deadlock avoided.** The revocation is followed by a 401, which would roll
it back, so the obvious fix is `REQUIRES_NEW`. But when a refresh has already
claimed a token (then finds the account disabled), its transaction holds that
row's lock; a new transaction revoking the family would wait for it while it
waits for the new one. The database cannot detect that, because one side is
idle rather than blocked. Instead `refresh` is
`@Transactional(noRollbackFor = BadCredentialsException.class)`.

**Two tabs.** Tokens are shared across tabs through localStorage. Two tabs
refreshing at once would send the same token twice and sign the user out. The
client now takes a cross-tab Web Lock before refreshing and, once it has it,
uses the stored token if another tab already replaced it.

**Sign-out** calls `POST /auth/logout`, which revokes the family.

**Limit that remains.** An access token cannot be revoked; it lives at most
15 minutes, so that is the window after sign-out or detected theft.

### Login limit

After 5 failed logins for one email within 15 minutes, further attempts get
`429 TOO_MANY_LOGIN_ATTEMPTS` with `Retry-After`, even with the right password.

- **Counted from the audit log,** whose `LOGIN_FAILED` rows already carry the
  email (V10 adds a partial index). Shared by every instance, survives
  restarts, and cannot be deleted, so there is no counter to reset and no Redis.
- **Per email, not per IP:** behind Railway every client appears as one of a
  few edge addresses (Known gaps), so a per-IP limit would throttle everyone together.
- **Unknown emails are limited identically,** and the check runs before the
  account lookup, so a 429 does not reveal whether an account exists.
- **A sliding window, not a lockout:** someone failing logins for a victim's
  email keeps them out only while they keep going, not until an admin intervenes.
- Refused attempts are audited as `LOGIN_THROTTLED` and do not extend the limit.

### Idempotent booking

`POST /appointments` accepts an `Idempotency-Key` header (V11). The key row is
inserted before the booking, in the same transaction, so a concurrent
duplicate waits on it: if the first commits, the duplicate gets its stored
response (`201`, `Idempotent-Replayed: true`); if it rolls back, the duplicate
books. Keys are scoped per user, a key reused with a different body is `422`,
failures are not stored (so retrying a 409 runs again), and keys expire after
24 hours.

The database already refused a duplicate booking; what the key fixes is the
**answer**. Without it, a retry after a dropped response gets a 409 "you
already have an appointment" for a booking that succeeded. The web app now
sends a key per attempt and retries network failures automatically, which is
only safe because of the key.

CORS had to allow the new request header and expose `Retry-After` and
`Idempotent-Replayed`; without that the browser would block the deployed app's
booking request at preflight.

**Verified by.** `SessionSecurityTest` (11): rotation; reuse revokes the
family and is audited; reuse leaves other sessions alone; 8 simultaneous
refreshes with one token leave at most one winner and no live token; logout;
access token refused as refresh token; disabled account; only the hash
stored; 5 failures then 429 with `Retry-After` even for the right password;
4 failures still allow sign-in; limit is per account; unknown email limited
the same. `IdempotentBookingTest` (7): replay, no-key conflict, key reuse,
per-user scope, failures not stored, 8 simultaneous duplicates yield one
booking and 7 replays, malformed key. A legacy JWT refresh token is still
refused as a bearer token.

**Deploying.** Refresh tokens issued before this change are not in the table,
so every signed-in user is asked to sign in again once, when their access
token next expires.

**Verified in production** after merging, without changing demo data:
- the refresh token is now a 43-character opaque value;
- refreshing works once; reusing the spent token gets 401, and so does the
  newest token in that session;
- a separate sign-in keeps working; logout returns 204 and the token is
  refused afterwards;
- five failed logins for an unused email get 401, the sixth 429 with
  `Retry-After: 897`;
- a malformed `Idempotency-Key` gets 400 before anything is booked;
- a browser preflight from the Vercel origin allows `Idempotency-Key`;
- `REFRESH_TOKEN_REUSED`, `LOGIN_THROTTLED` and `LOGOUT` appear in the audit log.

The first run reported `Retry-After` missing. Railway's edge answers over
HTTP/2, which sends header names in lower case, and the check looked the
header up by exact case. The header was there.

---

## 13. Frontend tests (PR #17)

The web app had no tests. Week 3 put logic in it that fails silently: if two
requests refresh with the same token, or a retry sends a new idempotency key,
nothing errors locally; a user is signed out, or books twice, in production.

**Tools.** Vitest (shares the Vite config, so no separate build setup) with
jsdom and Testing Library. `fetch` is replaced per test with a function that
records each request and states the server's answer, so a test reads as the
conversation between browser and API.

**What is covered (19 tests):**
- `api/client.ts`: three simultaneous 401s cause one refresh and three
  replays; a token another tab already refreshed is used instead of spending
  the old one; a refused refresh clears the session; a server that always
  answers 401 is replayed once, not forever; a non-JSON 502 page becomes a
  normal error; sign-out sends the refresh token.
- `BookingPage`: a network failure is retried with the same key; an error the
  server answered is not retried; resubmitting the same slot and reason keeps
  the key while changing the reason makes a new one (reusing it would be
  refused as `IDEMPOTENCY_KEY_REUSED`); a lost race shows the message and
  reloads the free slots.
- `lib/format.ts`: age on and around the birthday, initials without "Dr.".

**Checked that they can fail.** Making the page create a new key on every
attempt, and removing the "another tab already refreshed" check, each broke
the tests aimed at them (3 failures); both changes were then reverted.

**A test bug found on the way.** The first run failed one refresh test
because the previous test's fake `navigator.locks` was still installed:
`vi.stubGlobal` persists across tests unless `unstubGlobals` is set. Fixed in
the config, not by reordering tests.

**CI** runs `npm test` in the frontend job, between lint and build.

---

## 14. Metrics (PR #18)

**Two things found before adding anything.**
- `application.yml` listed `prometheus` among the exposed endpoints, but the
  endpoint never existed: it is only created when a Prometheus registry is on
  the classpath, and none was. `micrometer-registry-prometheus` is now added.
- `/actuator/metrics` fell under the default "any authenticated user" rule, so
  any signed-in patient could read the system's route list, error rates and
  JVM internals. Everything under `/actuator` except health is now ADMIN-only.
  Railway's health check (`/actuator/health/readiness`) is unaffected.

**Business counters** (`common/DomainMetrics`). HTTP metrics show a route's
volume and latency, but a lost booking race and a booking too close to now are
both just a 4xx on `POST /appointments`. So:
- `medicity_bookings_total{outcome}`: `booked`, `slot_already_booked`,
  `patient_double_booked`, `slot_too_soon`, and the rest.
- `medicity_logins_total{outcome}`: `success`, `failed`, `throttled`, `disabled`.
- `medicity_refresh_token_reuse_total`: each one ended a session.
- `medicity_idempotency_replays_total`.

Outcomes are a fixed set of values. Tagging by email or slot id would create a
time series per value, which is how a metrics backend runs out of memory.

**Counted after commit.** A booking is counted in an `afterCommit` callback,
not at the insert. Counting at the insert would include bookings whose
transaction later rolled back, and the metric would disagree with the
database. `MetricsTest.rolledBackBookingIsNotCounted` books inside a
transaction that is then rolled back and checks the counter did not move.

**Latency.** `http.server.requests` publishes histogram buckets, so
Prometheus can compute p95 and p99 across instances. Percentiles computed in
each instance cannot be combined into one.

**Verified by.** `MetricsTest` (3): anonymous 401, patient 403, admin 200 with
histogram buckets and the application tag, health still public; a booking and
a lost race move different counters; a rolled-back booking is not counted.

**Verified in production** after merging: the admin gets `/actuator/prometheus`
with histogram buckets, the application tag and `medicity_logins_total`; a
patient now gets 403 from `/actuator/metrics` (200 before); anonymous gets 401;
`/actuator/health/readiness` stays public for Railway's health check.

---

## 15. Endpoint denials on long paths were never audited (PR #20)

Found in CI logs while working on metrics:
`AUDIT WRITE FAILED ... value too long for type character varying(64)`.

A role-level denial is recorded as entity type `ENDPOINT` with entity id
`METHOD /path`. `audit_log.entity_id` was `VARCHAR(64)`, and a path carrying a
UUID is longer: `POST /api/v1/pharmacy/prescriptions/<uuid>/dispense` is 81
characters. Denials are written best-effort, so the 403 still went out and only
an ERROR line was logged. The audit row, the thing the write existed for, was
lost. Every existing test passed, because none looked for that row.

**Fix.**
- V12 widens the column to `VARCHAR(255)`. Widening a VARCHAR in PostgreSQL is
  a catalog change with no table rewrite, so no audit row is touched and the
  append-only triggers are not involved.
- `AuditLog` also cuts ids longer than the column (ending them with "…"). The
  path is chosen by the client, so without this a long enough URL would still
  be a way to be refused without leaving a trace.

**Verified by.** `AuditTrailTest.longPathDenialIsRecorded` (a patient calling
dispense leaves an `ACCESS_DENIED` row with the full path) and
`overlongEntityIdIsTruncated` (a 500-character id is stored cut to 255).
In production after merging, a patient calling dispense on an 81-character
path got 403 and the `ACCESS_DENIED` row appeared in the audit log.

**Lesson.** A best-effort write that fails quietly needs a test that checks
the write happened, not only that the response was right.

---

## 16. Load test: the booking guarantee under real concurrency (PR #19)

The concurrency tests prove the invariant with threads inside one JVM. This
runs the real Docker image against PostgreSQL 18 over HTTP, the way production
runs, and then checks the database rather than trusting status codes.

**Setup.** `.github/workflows/load-test.yml` starts the API image and
PostgreSQL with Docker Compose on a GitHub-hosted runner, seeds
`loadtest/seed.sql`, runs `loadtest/booking.js` with k6, and runs
`loadtest/verify.sql`. The API, the database and k6 share one machine, so the
latencies describe that machine, not production hardware.

**Scenarios.**
- *contention*: 200 patients book at once, 10 on each of 20 slots.
  Threshold: exactly 20 `201` and 180 `409 SLOT_ALREADY_BOOKED`.
- *booking_throughput*: a fixed arrival rate of bookings on free slots, so
  it measures the cost of a booking, not of a race.
- *reads*: 60 requests/s of browsing (doctors, slots, the portal list)
  alongside.

**Database checks after every run:** no slot with two active appointments;
each of the 20 contended slots booked exactly once; no patient holding two
appointments at one instant; one `APPOINTMENT_BOOKED` audit row per booking.

**Results.**

| Booking rate | Bookings | Booking p95 / p99 | Read p95 | Errors | Dropped | DB checks |
|---|---|---|---|---|---|---|
| 40/s for 60 s | 2,420 | 16 ms / 71 ms | 6 ms | 0 | 0 | all pass |
| 150/s for 20 s | 3,021 | 31 ms / 92 ms | 28 ms | 0 | 0 | all pass |
| 300/s for 10 s | 2,783 | 1,257 ms / 1,621 ms | 1,043 ms | 0 | 246 | all pass |

Contention produced exactly 20 winners and 180 clean 409s in every run.

**The numbers vary between runs.** GitHub's runners are shared machines. The
last run at 40 bookings/s, after the metrics PR merged, gave a booking p95 of
71 ms and p99 of 145 ms, against 16 ms and 71 ms in the first. Correctness did
not vary: 20 winners, 180 refusals, all database checks passing. The server's
own counters (`medicity_bookings_total`) matched k6 exactly in that run: 2,421
booked and 180 `slot_already_booked`. Quote latency as a range from these runs,
not as one number.

**Reading it.** Up to 150 bookings/s the runner kept p95 near 30 ms. At 300/s
it saturated: latency passed a second and k6 dropped 246 iterations because
every virtual user was waiting. That run fails its latency thresholds, as it
should. What did not change at any rate: zero errors, zero double bookings,
every booking audited. Under overload the system slowed down; it did not
become wrong.

**Two bugs in the test itself, caught before the first run.**
- VU ids in k6 are shared across scenarios, so the contention scenario could
  not use them to pick 200 distinct patients; it uses its iteration number.
- The first cold doctor's slots started at the same instants as the hot slots,
  so a patient who won a hot slot could be sent to a cold one at the same
  time and get a correct `PATIENT_DOUBLE_BOOKED`, counted as a failure. Cold
  grids now start 7, 14 and 21 minutes past the hot one.

**Seeding.** Load-test passwords are hashed by pgcrypto at BCrypt cost 4.
The application uses 12, and 300 logins at about 250 ms each would dominate
setup; Spring's verifier reads the cost from the hash, so both work.

---

## 17. Scheduled jobs: cleanup and unclosed visits (PR #22)

**One instance per job, without a lock table.** Every API instance has the
same `@Scheduled` methods, so with two instances each job would run twice.
Each job first calls `pg_try_advisory_xact_lock(hashtext('medicity.job.<name>'))`
inside its transaction. The first instance gets the lock; others get `false`
and skip. The lock is transaction-scoped, so it is released at commit, at
rollback, or when a crashed instance's connection closes. A session lock
would need an explicit unlock, and a missed one would block the job for good.
`ScheduledJobsTest.onlyOneInstanceRunsAJob` holds the lock from another
thread and checks that the job skips, then runs once it is released.

**Housekeeping** (nightly, 03:30 UTC) deletes refresh tokens past their expiry
and idempotency keys older than a day, in batches of 5,000 per statement.
Spent refresh tokens are deliberately kept until they expire: reuse detection
needs to recognise one when it comes back. This closes the "tables grow
forever" gap.

**Unclosed visits** (hourly). A visit still `BOOKED` a day after its slot
ended is marked `NO_SHOW` and audited as `VISIT_AUTO_CLOSED` with no actor, so
it is never mistaken for a doctor's decision.
- *One conditional `UPDATE ... RETURNING`*, not load-and-save, so a visit the
  doctor closes at the same moment is decided by the row lock. The job bumps
  `version`, so a doctor holding the old version gets `409` rather than
  silently overwriting.
- *The job can be wrong.* A doctor who saw the patient but forgot to close the
  visit would leave a false no-show on the patient's record. So a doctor can
  now mark a missed visit as seen (`NO_SHOW` to `COMPLETED`), audited with
  `from: NO_SHOW`; the visit page offers "Patient was seen after all". The
  other direction stays refused.

**Jobs are off in integration tests** (`medicity.jobs.enabled=false` on the
test base class), which call them directly. A job firing on its own schedule
mid-test would change that test's data under it.

**Effect on the demo.** Past demo visits nobody closed, such as Arjun's, will
be marked missed about an hour after deploying. That is the job working; the
demo reset (next) restores fresh visits.

**Verified by.** `ScheduledJobsTest` (5): a stale visit is marked missed and
audited while a recent one and a completed one are untouched, and a second run
changes nothing; the doctor can correct an auto-closed visit; the job bumps
the version; the lock makes a second instance skip; housekeeping deletes only
expired tokens and day-old keys and keeps spent-but-unexpired tokens.

---

## 18. Transactional outbox and in-app notifications (PR #23)

**The problem an outbox solves.** After a booking, someone must be told. Sending
the notification inside the booking's transaction ties the booking to the
notifier: if the notifier is slow the booking is slow, and if it fails the
booking rolls back. Sending it after commit loses it whenever the process dies
in between. Sending it before commit announces bookings that may still roll
back.

**How it works (V13).**
- The change writes an `outbox_events` row in its own transaction
  (`Outbox.publish` is `MANDATORY`), so the event exists if and only if the
  change committed. `OutboxNotificationsTest.failedBookingWritesNoEvent`
  checks that a lost race leaves no event.
- `OutboxRelay` delivers events afterwards. It claims one due event at a time
  with `FOR UPDATE SKIP LOCKED`, so any number of instances can drain the
  outbox without coordinating: each skips rows another has locked. Four relays
  draining 40 events at once deliver each exactly once.
- One transaction per event: the consumer's writes and the "published" mark
  commit together. A failure rolls that back and is recorded in a second
  transaction, with exponential backoff (5 s, 10 s, 20 s, ... capped at an
  hour). After 10 attempts the event is set aside (`failed_at`) instead of
  being retried forever.
- Delivery is at least once: a crash after delivering but before marking
  delivers again. The consumer is idempotent through a unique
  `(event_id, user_id)` on notifications and `ON CONFLICT DO NOTHING`.

**Two lock patterns, on purpose.** The scheduled jobs (entry 17) take an
advisory lock, because each run must happen once. The relay uses
`SKIP LOCKED`, because its work is many independent events that any instance
can take.

**Events and who is told.**

| Event | Notified |
|---|---|
| `APPOINTMENT_BOOKED` | the patient |
| `APPOINTMENT_CANCELLED` | the doctor (the patient cancelled it) |
| `PRESCRIPTION_ISSUED` | the patient |
| `PRESCRIPTION_CORRECTED` | the patient, and if the original was already dispensed, every admin (standing in for the pharmacy) |

The last closes a known gap: a prescription corrected after dispensing used to
reach no one.

**Payloads carry what the consumer needs** (names, the visit time) rather than
ids to look up later: by the time the relay runs, the appointment may have
been cancelled or the prescription corrected again.

**Time zones.** A notification stores the visit time as an instant
(`occurs_at`); the browser formats it. The server never renders a local time
it would have to guess.

**API and UI.** `GET /api/v1/notifications` (latest 50 and the unread count),
`POST /{id}/read`, `POST /read-all`, all scoped to the token's user; marking
someone else's notification answers 404. The header shows a Notifications
link with an unread badge, polled once a minute; a push channel would be more
infrastructure than this needs.

**Not built.** Email and SMS would be further consumers of the same events.
No provider is configured, and a stub that pretended to send would be worse
than none.

**Verified by.** `OutboxNotificationsTest` (8) and `NotificationsPage.test.tsx` (3).

---

## 19. Nightly demo reset (PR #24)

The public demo is shared and used up by use: the first visitor to close
Dr. Rao's waiting visit takes it away from everyone after, bookings fill the
open slots, and the seed's "today" visits drift into the past (and, since
entry 17, are marked missed a day later).

`DemoResetJob` runs at 02:30 UTC (08:00 in India) under the `demo` profile
only, the same switch that loads the seed, so a real deployment never has the
bean. It:
1. deletes every appointment with a demo doctor or for a demo patient,
   with its prescriptions, items and dispensations, then the demo doctors'
   slots (children first; an original prescription and its correction go in
   one statement, so the self-reference holds when the statement ends);
2. clears notifications and stored idempotent responses, which describe
   bookings that no longer exist;
3. puts demo stock back to 250 through an `ADJUSTMENT` movement, so on-hand
   stock stays reconstructable from the ledger;
4. re-runs the demo seed, whose dates are relative to now.

Visitors' accounts are kept; their bookings with demo doctors go with the rest.

**All or nothing.** Everything, including the seed script (run on the
transaction's own connection), is one transaction. If re-seeding fails, the
deletions roll back too, and the demo stays as it was instead of empty.
`DemoResetTest.resetIsAllOrNothing` runs the job with a seed that fails and
checks that nothing was deleted.

**Tested without the demo profile.** Running a test class under `demo` would
load the seed into the database every other test class shares. The test
builds the job by hand and removes what it seeded afterwards.

**Also:** the landing page says the demo resets nightly. The smoke-test
booking left on production (on a demo doctor's slot) is removed by the first
reset.

**Verified by.** `DemoResetTest` (3): seeding produces Meera's history, Dr.
Rao's day and full stock; a day of visitor changes is undone while the
visitor's account stays, and stock is restored through the ledger; a failing
seed leaves everything in place.

---

## 20. Production outage: the demo reset bean could not be created (PR #25)

**What happened.** After #24 merged, production answered 502 ("Application
failed to respond") for over ten minutes. `DemoResetJob` had two constructors:
the public one, and a package-private one added so the test could pass a
failing seed script. With more than one constructor and none marked
`@Autowired`, Spring cannot choose; it falls back to a no-argument
constructor, finds none, and refuses to create the bean
(`No default constructor found`). The application context fails and the
process never listens.

**Why CI did not catch it.** The bean is `@Profile("demo")`. Production runs
the demo profile; the integration tests deliberately do not (it would load the
demo seed into the shared test database), and `DemoResetTest` built the job
by hand with `new`. So nothing in CI ever asked Spring to construct it.

**Fix.** `@Autowired` on the public constructor.
`DemoResetWiringTest` starts a small Spring context under the demo profile
with the job's dependencies mocked and asserts the bean is created. It fails
against the old code with the exact production error and passes with the fix.

**Lesson.** Constructing a bean by hand in a test proves the logic, not the
wiring. Any bean that only exists under a profile the tests do not run needs
a test that lets Spring build it.

---

## 21. Neighbourhood chemists: stores, "near me", and "cheaper brand is OK" (PR #26)

First of the pharmacy-network features. Chemists outside the hospital get
accounts, and patients can find verified stores near them.

**A new role, and a gate on it.** `CHEMIST` joins the role CHECK (V14). Anyone
can sign up a store at `/api/v1/auth/register/chemist`, but the store has
`verified_at IS NULL` until an administrator has checked its drug licence.
Nothing that reaches patients' data (the question queue, from the next PR)
looks at unverified stores, so signing up is not a way to see what people
nearby are asking for. The admin is notified through the outbox; the chemist
is notified when verified.

**Account and store are one unit.** `AuthService.createAccount` is
`MANDATORY`, called from the store sign-up's transaction. A licence that is
already registered (unique index on `upper(licence_number)`, so case does not
dodge it) rolls back the account as well: the test checks that no chemist
account is left without a store. The licence cannot be edited afterwards,
because it is what was verified.

**"Near me" without PostGIS.** The hosted Postgres has no PostGIS. Distance is
a SQL function (`store_distance_m`, haversine), and the query first cuts the
table with a bounding box on an ordinary `(latitude, longitude)` index, then
computes the exact distance for the few rows left. Two details:

- The function takes `double precision`. Declared with `numeric`, a Java
  `double` would not convert implicitly and the call would not resolve.
- The box bounds are cast to `numeric`. Compared with a bound double, the
  numeric columns would be converted row by row and the index could not be used.

A test puts a store in the corner of the search square (3.5 km away on a
3 km search) and checks it is excluded: the box is a prefilter, not the answer.
The same `WITHIN` fragment will pick the stores a patient's question goes to,
so the list a patient sees and the stores asked are the same by construction.

**Opening hours** are local wall-clock times in the store's zone, not
instants. A closing time before the opening time means open past midnight.
Unit tests cover the boundaries, past-midnight and 24-hour stores, and that
"open now" uses India time whatever the server's zone.

**Cheaper brand with the same ingredients.** `prescription_items` gets
`substitution_allowed` (default false). The doctor ticks it per medicine; the
patient sees "Cheaper brand OK" on the prescription, and the next PR lets a
chemist offer another brand only where it is ticked. Absent in the request
means no: a substitution the doctor did not allow is a call back to the clinic.

**Demo.** Five verified stores around Indiranagar (sign in as
`chemist@medicity.demo`), and four more brands of existing medicines (Crocin,
Azee, Glycomet, Omez) so substitutions have something to offer. The nightly
reset restores the demo stores' profiles.

---

## 22. Ask every chemist nearby, and compare their answers (PR #27)

The core of the pharmacy network (features 3, 4, 15 and 16). A patient sends
one of their prescriptions to every verified store within 1, 3 or 5 km. Each
store answers per medicine: yes, partly (how many) or no, with its price, and
may offer another brand where the doctor allowed one. The patient sees the
answers ranked side by side.

**The question is always a real prescription.** A patient cannot type in
medicines: a question is built from a current (not superseded) prescription
the doctor issued in Medicity. The store sees the doctor's name, speciality
and registration number, the issue date, and whether the hospital pharmacy
already dispensed it. That is the answer to fake prescriptions: the chemist
knows it came from the doctor's own account, not a photo a patient uploaded.

**Minimum necessary.** Stores see "Meera N.", the medicines, dose and
duration, not the diagnosis or the full name. The patient's location is kept
for demand counts (next PRs) and never shown to a store. Every store read of
a question is audited; a store that was not asked gets 404, audited as a denial.

**What the database decides:**

- *One open question per prescription*: a partial unique index on
  `prescription_id WHERE status IN ('OPEN', 'RESERVED')`. Two taps on "Ask"
  cannot send every store the same question twice. An expired question is
  marked expired just before inserting, so asking again after six hours works
  even before the expiry job exists.
- *A store answers once, and only while the question is open*: the answer is
  one conditional `UPDATE request_recipients ... FROM medicine_requests WHERE
  status = 'PENDING' AND request open AND not expired`. Nothing is read first.
  A test runs 8 concurrent answers from one store: 1 wins, 2 answer lines
  exist (one per medicine), 7 get 409.
- *Answer lines belong to the question*: composite foreign keys to the
  store's recipient row and to the question's items; a CHECK says "no" has
  quantity 0 and no price, and anything else has both.

What the service checks, because it needs other rows: every medicine is
answered once; "partly" is between 1 and one less than asked; another brand
is allowed only where the doctor ticked it, and only a brand with the same
ingredient, strength and form from the catalogue.

**Fan-out** uses the same `WITHIN` SQL as the "near me" list, capped at the
nearest 25 stores, in the same transaction as the question. If no verified
store is in reach, the patient is told and nothing is saved. Store owners are
notified through the outbox, and the patient is notified as answers arrive.
A cap of 5 open questions per patient stops one account from flooding a
neighbourhood's queues.

**Ranking** happens on the server so every device shows the same order:
answered before unanswered; everything in full before partial; more medicines;
cheaper total; nearer. "Cheapest" and "Nearest" labels go on stores that have
everything. A unit test pins that a complete, dearer store ranks above a
cheap partial one.

**Found by CI: constraint names from plain JDBC.** The first run returned
500 instead of 409 for a second question on the same prescription.
`Constraints.nameOf` looked for the constraint name only on Hibernate's
exception, and these writes go through `JdbcTemplate`, where there is none. It
now also reads the name from the driver's `PSQLException`, which needed the
driver at compile scope. Every earlier constraint-to-status translation went
through Hibernate, which is why this had not come up before.

**Demo.** Meera has an open question about her omeprazole. Three stores have
answered: one in full, one with the cheaper Omez brand the doctor allowed,
one partly. Sri Sai Medicals (`chemist@medicity.demo`) and one other store
still have it waiting in their queues.

---

## 23. Reserve at a store, pick up with a code (PR #28)

Feature 5. The patient picks one store's answer. The store keeps what it has
aside for its hold time (2 to 4 hours, the store's own setting) and the
patient gets a six-digit code. At the counter the chemist types in the code
the patient shows, and that is what marks the medicines collected.

**The store never sees the code.** It is in the patient's response only, and
only while the reservation is held; a test checks that the store's list does
not contain it. So a store cannot mark something collected without the
patient there, and someone who only knows the patient's name cannot collect
her medicines. At the counter the store does get her full name, now that she
chose the store; before that it only had "Meera N.".

**What the database decides:**

- *One reservation per question*: reserving moves the question from OPEN to
  RESERVED in one conditional UPDATE. A partial unique index on live
  reservations backs it up. A test races 8 reservations, alternating between
  two stores: exactly one holds.
- *Only a store that answered*: the reservation's foreign key is the store's
  recipient row, and the service refuses a store that has nothing.
- *Hold window*: a CHECK keeps `expires_at` within 4 hours of creation,
  whatever the code does.
- *Collecting* is one UPDATE whose WHERE clause requires: held, not expired,
  fewer than five wrong codes, and a matching code.

**Wrong codes count even though they fail.** Six digits can be guessed with
enough tries, so five wrong codes lock the reservation against codes. The
patient can cancel and reserve again. The count is written and then a 422 is
thrown, so `collect` is `@Transactional(noRollbackFor =
WrongPickupCodeException.class)`: without it, the rollback would erase the
count and every guess would be free. This is the same pattern as refresh-token
reuse (entry 12), and a test checks that the count is on record after the error.

**Expiry** is a job every minute (advisory-locked like the others). Held
reservations past their time expire and their question reopens, if its own six
hours are not up. Both sides are told. Open questions past six hours are closed
by the same job. The job makes expiry visible; it does not make it true.
Collecting checks the time in its own UPDATE, so an overdue reservation cannot
be collected even if the job is late.

**Where it was collected** now shows on the patient's prescription ("Collected
at Sri Sai Medicals, 27 Sep"). Refill reminders will build on that date.

---

## 24. What people nearby ask for, and optional live stock (PR #29)

Features 17 and 18.

**Insights for stores.** "4 people near you asked for azithromycin this week;
you said no to 2 of them, and they reserved elsewhere." Per medicine, a store
sees: how often it was asked, by how many people, how many units; what it
answered; how often the patient went elsewhere. It also gets a "consider
stocking" flag when it said no or partly at least twice. The summary adds
questions received and answered, reservations and collections, and the median
time to answer a question by hand.

Privacy decides what is counted:
- *Only questions sent to this store.* A store learns nothing about questions
  it was never part of.
- *Counts only.* No patient, prescription or doctor appears.
- *At least two different patients per medicine* (`HAVING count(DISTINCT
  patient_id) >= 2`). A rare medicine would otherwise point at the one person
  nearby who takes it. A test checks that a medicine only one person asked for
  is not listed.

It is one aggregate query over the question tables with `FILTER` clauses, not
a separate analytics store. At this scale that is the right trade.

**Optional live stock.** Most stores will never use it: answering by hand is
the product. A store whose billing software knows its stock can send the whole
list (`PUT /api/v1/stores/me/stock`) and turn on automatic answers. A question
it receives is then answered in the same transaction that sends it, marked
"Live stock" for the patient.

- *Replaced whole, never patched.* Billing software knows its current stock,
  not what changed since Medicity last heard. A whole list also gives one
  freshness time for everything: a medicine missing from the latest upload is
  out of stock, not "unknown".
- *Only fresh stock answers.* Older than 24 hours, questions wait for the
  chemist again. A confident "yes" from yesterday's stock is worse than a
  slower real answer, and a test covers it.
- *Same rules as a person.* The automatic answer goes through the same path as
  a typed one: the same validation, the same conditional UPDATE, the same
  notification. It offers another brand only where the doctor allowed it (the
  cheapest other brand with enough stock), and "partly" when it has some. The
  answering code was split into `answer` (a chemist) and `answerAs` (a store,
  typed or automatic) for this.

**Demo.** A week of history: Arjun and Kavya had visits ending in
azithromycin, and with Meera asked the stores and collected. Sri Sai's
insights now show azithromycin, with no from them both times, went elsewhere
twice, and "consider stocking". Nightingale 24x7 has fresh live stock with
automatic answers on, so a visitor's new question gets one answer at once.

---

## 25. Refill and course reminders (PR #30)

Feature 6: "Your BP tablets run out in 3 days. Ask the stores again?"

**Derived, not stored.** A course starts on the day the medicines were handed
over, whichever came last: the hospital pharmacy's dispensing, or a
neighbourhood store's collection (entry 23). It ends `duration_days` later.
Nothing new is stored. The course is worked out each time from facts already
recorded, so there is no second copy of "when did she start" to fall out of
step with the dispensing records. One query with a `LATERAL` subquery picks the
latest fill per prescription.

**Two kinds of reminder, because they ask for different things.** A medicine
taken for 14 days or more (blood pressure, diabetes, reflux) gets "runs out in
N days" three days before the last dose. Its link opens the prescription with
"Ask chemists nearby" already open. A shorter course (an antibiotic) gets
"Last day tomorrow: finish the course, even if you feel better", and no
suggestion to buy more. Pushing refills of an antibiotic would be the wrong
thing to automate.

**Running the job twice sends nothing twice.** The morning job (09:00 India
time, after the demo reset) finds the same state on every run. Each reminder's
event id is derived from what it is about: a name-based UUID of the kind, the
prescription line and its last day. The new `Outbox.publishOnce` inserts with
`ON CONFLICT (event_id) DO NOTHING`. A second run publishes nothing, and a real
refill (a new last day) earns a new reminder. This is the idempotent-producer
half of the pattern; the notification consumer was already the idempotent
consumer half (entry 18). A test runs the job twice.

**Days are India days.** There is no per-patient time zone; every store and
doctor on the platform is in India, so dates are computed in Asia/Kolkata. That
is stated in the code rather than left to the server's zone.

**Portal.** "My medicines" lists running out, taking now, not collected yet,
and finished, with a progress bar per course.

**Demo.** Meera collected her 28-day omeprazole 25 days ago, so each morning
after the reset she gets "Omeprazole 20mg runs out in 2 days".

---

## 26. Family members on one account (PR #31)

Feature 7: parents and children under one sign-in. A family member is a
patient like any other: visits, prescriptions, questions to chemists and
reservations all hang off `patients.id`. The difference is that they have no
sign-in; the account holder (their guardian) acts for them.

**The model.** `patients.user_id` became nullable, and a new
`guardian_user_id` is set for family members, who keep their own name on the
patient row. A CHECK allows exactly one of the two; another requires a family
member to have a name and a relationship. So "a patient nobody can reach" and
"a patient with two owners" cannot be written. `Patient.displayName()` and
`Patient.accountUserId()` replace `getUser().getFullName()` and
`getUser().getId()` everywhere. The account is whom notifications go to, so
Meera is told "For Aarav, with Dr. Rao." and "Lalitha's Metformin 500mg runs
out in 2 days".

**Choosing whom to act for: one checked place.** The portal's routes still say
"me" and still take no patient id in the path (the IDOR design from entry 4).
A family member is chosen with an `X-Patient-Id` header. `ActingPatient` is
the only place it is read: the id must be the account's own patient or one of
its family members. Anything else answers 404, exactly like an id that does not
exist, and is audited. Every service that works "as the patient" (portal,
booking, questions, reservations, courses) now asks `ActingPatient`, so there
is no second, weaker check to find. Tests cover acting for another family's
child, for a random id, and for another account holder: all 404.

**Found while changing it:**

- *Inner joins would have hidden family members.* Three appointment queries
  did `JOIN FETCH p.user`. With `user_id` null for a family member, an inner
  join silently drops their visits from the doctor's schedule. These are now
  `LEFT JOIN FETCH`. The SQL in the chemist network reads names with
  `coalesce(pu.full_name, p.full_name)`.
- *The idempotency key had to include the patient.* The fingerprint was the
  route and body. Booking the same slot body with the same key, first for
  herself and then for her son, would have replayed her booking as his. The
  fingerprint is now `(patientId, body)`; a test checks that the second is
  refused as a reused key, not replayed.
- *CORS.* The new header had to be allowed, or the browser's preflight would
  fail before the request (the Idempotency-Key lesson from entry 12).

**Frontend.** A switcher in the portal sidebar ("Lalitha Nair · Parent") sets
whom the portal is for. Switching removes every cached query except the family
list, so one person's records never appear under another's name. The header
is added only to patient requests (`/patients/me/*` except the family list,
and `/appointments`), with a client test. Booking pages say "Booking for
Lalitha Nair".

**Limits.** At most 8 family members per account. That is checked, not
constrained: two simultaneous adds at the limit could make nine, and that is
not worth a lock.

**Demo.** Meera manages her mother Lalitha (metformin 500mg for diabetes,
dispensed 27 days into a 30-day course) and her son Aarav. The nightly reset
removes any family members visitors add to the demo accounts.

---

## 27. How to take your medicines, in your own language (PR #32)

Feature 8. Each prescription in the portal has a "How to take" panel in
English, Hindi, Tamil, Kannada, Telugu or Bengali, with a "Share on WhatsApp"
button for the family member who looks after an elderly parent's medicines.

**Not machine translation.** The doctor writes the frequency as short free
text ("Twice daily after food"). Running free text through a translator is how
"once" becomes "one tablet" or "before food" becomes "with food", and for
medicines that is the dangerous failure. So nothing is translated. The
frequency is parsed against a fixed set of recognised phrases into structured
parts: how often, morning or night, before, after or with food, only when
needed, a daily maximum, what for. Each part is written from a hand-made
phrasebook. The parts are shown separately ("दिन में दो बार · खाने के बाद · 5
दिन तक"), never joined into sentences, so no grammar has to be generated.

**All or nothing.** If any word of the frequency is not recognised, none of it
is shown translated. The patient sees the doctor's own English words and, in
their language, "ask your pharmacist to explain these instructions". A
partial translation that drops "reduce to once after a week" would be worse
than none. The doctor's words are always shown underneath the translation
anyway.

**Helping doctors write recognisable phrases.** The frequency field suggests
common phrases (a `datalist`). If the doctor types something the parser will
not recognise, the form says quietly "Patients will see these words in English
only". A test checks that every suggested phrase is recognised, so the
suggestions and the parser cannot drift apart.

**Sharing.** The WhatsApp message has the medicines, doses and instructions
(with the doctor's English), and never the diagnosis: a forwarded message can
go anywhere. A test checks the diagnosis is absent. It is a `wa.me` link the
patient taps, and nothing is sent by the server.

**Known gap.** The phrasebook was written without review by native speakers
or a pharmacist. It covers a small, simple vocabulary on purpose, but it must
be reviewed before real patients rely on it (listed under Known gaps).

---

## 28. The handwritten prescription: photo, AI draft, doctor confirms (PR #33)

Features 10 and 11. Most doctors in India write by hand, and asking them to
type is how a platform loses them. So the doctor photographs the slip. Claude
reads it into a draft, and the doctor checks and confirms that draft in the
usual prescription form. The photo stays attached, so the patient and every
store she asks can compare the typed list with what the doctor actually wrote.

**The model's output is never a prescription.** Uploading stores the photo.
Reading returns a draft and records what the model said on the photo row.
Neither writes a prescription. The prescription is issued only by the
doctor's normal "issue" request, which now names the photo it was typed from
(`scanId`). So a misread medicine, an invented dose, or text on the slip
saying "ignore previous instructions" can at worst produce a wrong draft that
the doctor sees and corrects. A test reads a photo and checks that the visit
still has no prescription.

**Matching is strict on purpose.** The model returns names as written; the
server matches them to the catalogue only when exactly one entry fits by name
(brand or generic) and strength. When two could fit, nothing is chosen: an
empty field that says "read as 'Pan-D 40', choose the medicine" is harder to
overlook than a wrong one filled in. Each line in the form shows what was read
from the slip.

**Trust on upload.** The browser's declared content type is not trusted: the
first bytes decide (JPEG, PNG and WebP signatures). The size is capped at
5 MB twice, by Spring's multipart limit (413) and by a CHECK on the column.
The service checks the photo is from the doctor's own completed visit, and
that it is attached only to a prescription for that same visit. A unique index
means one photo issues one prescription.

**Who sees the original:**
- the doctor who took it;
- the patient, or the account acting for them;
- a store the patient's question was sent to (other stores get 404, and each
  view is audited).

Images are served with `Cache-Control: no-store, private`, and the browser
fetches them with the bearer token. An `<img src>` cannot carry the token, so
the page fetches the bytes and shows them from an object URL, released when
closed.

**Without an API key.** `ClaudePrescriptionReader` calls the Messages API with
the photo as a base64 image block and asks for JSON only. It is used only when
`ANTHROPIC_API_KEY` is set. Without one, "read" answers "not switched on here;
type the medicines", and the photo is still attached, which is itself the
feature for patients and stores. The reader is unit-tested against a mock
server: the request's headers, model and image block; a reply wrapped in a code
fence; and a reply that is not JSON, or an API error, failing cleanly. The
integration tests stub the reader.

**The bean had two constructors**, one for tests with a mock HTTP client. It
has `@Autowired` on the public one, because the outage in entry 20 was exactly
this.

**Storage.** Photos live in Postgres (`bytea`, capped). At this scale that
means one backup and one access-control path. At a larger scale the bytes would
move to object storage and this row would stay as the index.

---

## 29. Free for doctors and chemists, and the landing page for all of it (PR #34)

Feature 13 is a business decision, not code: doctors and chemists pay nothing.
Paid clinic software charges a monthly fee, and a small-town doctor or chemist
is exactly who that turns away. The code's part is to not stand in the way:
- a doctor can keep writing by hand (entry 28);
- a chemist needs no stock system (entry 22);
- live stock stays optional (entry 24);
- signing up is self-service, with the licence check done by a person.

The landing page now explains the chemist network: ask every store, compare,
reserve and pick up; refill reminders, family members and instructions in your
language. It also has a section on being free for doctors and chemists, a
"For chemists" link, and the demo logins for all three roles. The README's
invariants table and API list cover the new tables and endpoints, and a
"chemist network" section summarises entries 21 to 28.

---

## 30. Before the write-up: the gaps list, tests for the untested pages, the header in the docs

A check before Week 6 found three loose ends from the chemist-network work.

**The Known gaps list was behind.** It named the unreviewed phrasebook but not
the other shortcuts taken in PRs #26–#33. Six bullets were added below; each
was checked against the code rather than written from memory (the family cap
comment in `FamilyController` already said "not race-proof"; the open-question
count in `MedicineRequestService.ask` is a plain `SELECT count(*)`).

**Four pages had no frontend tests**, including the one where a chemist types
the patient's code. New tests, all against a mocked server:

- `ReservationsPage`: non-digits are dropped, the button stays disabled until
  six digits, a wrong code shows the server's "attempts left" message and
  clears the box, the right code empties the list, and a locked reservation
  offers no code box at all;
- `FamilyPage`: adding a member sends exactly the fields filled in, and a
  refusal (the cap) keeps the form and its contents;
- `StockPage`: sending replaces the whole list and leaves out half-filled
  rows; stale stock says it will not answer by itself; the auto-answer toggle;
- `PendingStoresPage`: verifying takes a store off the list, and a
  non-administrator's visit makes no request at all.

`test/renderPage.tsx` holds the query-client-and-router wrapper the page tests
had each been repeating. Frontend tests: 41 to 50.

**`X-Patient-Id` was missing from the API reference.** `ActingPatient` reads
the header from the request, never as a controller parameter, so springdoc had
no way to see it. An `OpenApiCustomizer` adds it (optional, uuid) to every
route under `/api/v1/patients/me` except `/family`, plus booking and "my
appointments". `ApiDocsTest` fetches `/v3/api-docs` and asserts where it
appears and that it is absent from the family and store routes, so a new route
cannot quietly drift from the rule.

## 31. The landing and sign-in pages, redesigned around the line

The old landing page described features in cards. The new one shows the
mechanism: the product is one journey (book, visit, prescription, chemists
nearby, pick up), so the page is drawn as a metro line with five stations, and
the sign-in pages give each role its own line colour.

**What is on the page, and why it is honest.** The hero map places the five
demo chemists by their seeded latitude and longitude around the demo patient's
home (`lib/demo.ts`, kilometres per degree at Bengaluru's latitude), with the
seed's real answers to her omeprazole question: Green Cross ₹77.00, Lakshmi
₹58.80 with Omez, Nightingale 10 of 14. Every station shows a fragment of a
real screen with demo data, and the page says it is demo data. No ratings,
users or numbers were invented. Copy claims were checked against the code; one
("reminded the day before your visit") was cut because no such reminder exists.

**Three sign-in URLs, one component.** `/login`, `/login/doctor` and
`/login/chemist` render the same `LoginPage` with a `role`. React keeps the
instance between them, so switching role slides the coloured tab and the side
panel changes colour rather than a new page loading. Each has a one-click
sign-in as that role's demo account; the password was already public on the
landing page. `ProtectedRoute` now sends `/doctor/*` to the doctor sign-in and
`/store/*` to the chemist one.

**Motion without hiding content.** Reveals use IntersectionObserver, never a
scroll listener. Where it does not exist (tests, old browsers) everything is
shown at once, and `prefers-reduced-motion` shows the map's final state and
turns transitions off. The tagline lights word by word as it crosses a line
just below the middle of the screen.

**Scoped styles.** Everything lives under `.lm` in `landing.css` with its own
light and dark tokens, so the portal, doctor and store workspaces are
untouched. The app header is hidden on these routes because they carry their
own navigation, and the app shell renders a `div` instead of `main` there so
landmarks do not nest.

**Process.** The direction came from the design skills the user chose
(landing-page-design for structure and copy, impeccable for the visual world),
picked by the user from rolled options, and checked by a separate reviewer
agent against desktop and phone captures in both themes. The detector flagged
Geist as overused, so the face is Manrope. Tests: `LandingPage.test.tsx` (the
map's answers, stations, role links) and `LoginPage.test.tsx` (one-click demo
per role, role tabs, typed sign-in and the server's error). Frontend tests: 50
to 55.

## 32. Signing in with a mobile number and a code

Indian health platforms (Practo, Apollo 24|7, PharmEasy's pharmacy app) sign
people in with a mobile number and a one-time code, because many patients do
not use email. Medicity now does too, with email and password kept as the
alternative on every sign-in page.

**Which account a number belongs to.** `users.phone` was free-text contact
detail and not unique, so V20 adds `login_phone`: the number normalised to
+91 and ten digits, unique across accounts. Existing accounts got one only if
their number normalised cleanly and no other account shared it, so the
migration could not fail on production data it had never seen; two accounts
on one number keep email sign-in. New sign-ups set it, and a second account
on the same number is refused with `PHONE_TAKEN`. A chemist's account takes the store's number only when no
account has it yet, since a store number is often a landline or shared. `PhoneNumbers.normalise`
applies the same rule in Java, so "98765 00101", "+91-98765-00101" and
"919876500101" all find the same account.

**What a code is allowed to do.** Six digits, good for five minutes, five
guesses and one sign-in; only the newest code for an account counts. The
row stores an HMAC of the code keyed by the server secret and bound to the
row id, compared in constant time. A wrong guess is written before the
refusal and survives it (`noRollbackFor`), and the challenge row is locked
while checked, so parallel guesses are counted one by one. "Newest" is decided by an identity column,
not the timestamp: CI found two codes issued in the same instant, where the
older one still worked.

**What the replies give away: nothing.** "Send a code" answers every valid
number the same way, account or not. At most three codes are texted to an
account in fifteen minutes (bounding both guessing and SMS bombing), and a
request over the limit is answered like any other and sends nothing, so the
limit cannot be used to find accounts either.

**No SMS provider yet.** Texting Indian numbers needs a provider with DLT
template registration; `Msg91OtpSender` is written against MSG91's OTP API
(tested against a mock server, not yet the real service) and switches on
when `MSG91_AUTH_KEY` and `MSG91_OTP_TEMPLATE_ID` are set. Until then every
number is told plainly that codes are not available and offered email,
except the public demo accounts: in the demo profile their code is shown on
screen, so a visitor can try the flow.

Tests: `OtpSignInTest` (single use, any number format, five guesses, expiry,
newest code only, send limit, unknown numbers look the same, demo codes only
in demo, code stored as a hash, one account per number) and
`Msg91OtpSenderTest`; three new frontend tests for the demo code, the
"not switched on" path and a wrong code.

## 33. Two doors to a doctor: the body guide and the search box

Patients arrive in one of two states. Some know what they want ("Dr. Menon",
"a dermatologist"); some only know where it hurts. The landing page now opens
with a door for each, and the chemist map that used to be the first screen is
the section right after it.

**Door 1, the body guide.** A front and back drawing of the body, eleven
tappable areas. Choosing one opens its symptoms and "since when"; the answer
is the kind of doctor to see, why, and a link straight into the filtered
directory (`/doctors?specialty=Gastroenterology&zone=abdomen_upper`).

- **Deterministic and offline.** `bodymap/taxonomy.ts` is a static table: the
  same taps always give the same answer, instantly, and nothing is generated.
  A symptom can name a more specific speciality than its area (toothache sends
  the head to a dentist), and the area's default becomes the alternative.
- **Warning signs win.** Every area has at least one (crushing chest pressure,
  sudden worst headache, face drooping, black stools, a numb groin…). Ticking
  one replaces the recommendation with "get emergency care now" and tap-to-call
  links for 112 and the 108 ambulance.
- **Specialities are spelt as the data spells them,** so every answer is a
  working filter; a test fails if the table names a speciality no seeded
  doctor practises, or if the drawing and the table disagree about areas.
- **Accessible:** each area is one `role="button"` path (both arms are one
  path, so one tab stop), named, keyboard-operable, with a plain list of
  areas as the alternative to the drawing. Sheet, not modal: the body stays
  in view and choosing another area replaces the sheet.

**Door 2, the search box.** A combobox over the new
`GET /api/v1/doctors/suggest` (matching specialisations with doctor counts,
then up to five doctors by name or specialisation; fewer than two characters
answers nothing rather than everyone). Requests wait 300 ms after typing
stops and are cached, so retyping is instant; arrow keys, Enter and Escape
work as a listbox should. Six speciality shortcuts sit below it. `q` on the
directory now matches specialisations as well as names, and
`GET /api/v1/doctors/specialties` replaces the page's hard-coded list, so it
only offers specialities someone can be booked in.

**Everyday words.** Checking the live page found that the search box's own
example, "knee", found nothing: suggestions only matched names and
specialities. `wordMatches` now turns the body guide's areas and symptoms,
and a short list of common words (skin, tooth, fever, kidney…), into
specialities on the spot, shown first as "For what you described". Warning
signs are never offered as search suggestions.

**The directory reads its filters from the URL,** so both doors link into it
and a filtered list can be shared. Arriving from the guide shows which area
it was for, and an empty result offers the area's alternative speciality.

**Eleven more demo doctors** (general medicine, gastroenterology,
orthopaedics, ENT, dermatology, pulmonology, urology, gynaecology,
nephrology, general surgery, dentistry) so every answer finds someone to
book; the nightly reset clears their bookings too.

**Not built from the brief, and why:** clinics and hospitals as search
results (Medicity is one clinic; there is no facility table to search), and
distance for doctors (doctors have no location; the location bar belongs to
the chemist search).

Also: the frontend tests' async timeout is 3 s instead of 1 s. A test that
waits for a heading failed once when the whole suite ran on a busy machine
and passed alone; shared CI runners are busier still.

## 34. Doctors sign up themselves, and set the hours patients book

Until now the clinic created every doctor and the demo seed made their
slots. On Practo a doctor registers, gives the medical council and
registration number, and is listed once the number is checked. Medicity now
works the same way, and a doctor sets their own weekly hours.

**Sign-up and the check.** `POST /auth/register/doctor` takes the council,
registration number, qualifications, speciality (from the clinic's closed
list, so the directory and the body guide keep naming the same things), fee
and a mobile number (so the doctor can sign in with a code). The account
signs in at once, but `verified_at` is null: the doctor is left out of the
directory, the specialities, the search suggestions and the slot listing,
and `BookingService` refuses a slot of theirs even if its id was obtained
some other way. Admins get a notification and a queue (`/admin/doctors`,
beside the stores queue); verifying tells the doctor. The registration
number, like a store's licence, cannot be changed afterwards.

`verified_at` defaults to `now()` in V21 and in the entity: a doctor
inserted any other way (the clinic, the seed, tests) is one the clinic
vouches for, and sign-up is the single path that writes null, explicitly.

**Hours become slots.** One window per weekday in Asia/Kolkata
(`doctor_hours`). Saving replaces the doctor's future slots that nobody has
ever booked, then generates slots for the next four weeks; a slot with any
appointment row stays, and a new slot overlapping it is skipped by the
existing no-overlap exclusion constraint through `ON CONFLICT DO NOTHING`,
which also makes generation safe to repeat. `DoctorHoursJob` tops every
doctor up to four weeks ahead each morning (03:45 UTC, after the demo
reset). Slot times are computed in minutes of the day, so a window ending
near midnight cannot wrap into an endless loop. Hours can be set before
verification; they become bookable the moment the doctor is verified.

Tests: `DoctorSignUpTest` (unverified is invisible and unbookable, the
admin check and both notifications, exactly 80 slots for weekdays
10:00-12:00 over four weeks, all at 10:00-11:30 India time and none at
weekends, the nightly top-up adds nothing when nothing is missing, changing
hours keeps the booked slot, and the refusals); frontend tests for sign-up,
the hours page and the admin queue.

## 35. Before the write-up, again: docs, tests for the doctor's flows

A second check before Week 6. The README described neither the body guide
nor code sign-in, and did not list the demo mobile numbers; DESIGN.md was
written before the two doors and did not record them; the administrator's
header link still said "Stores to verify" though there are now two queues.

Frontend tests were missing on the flows an evaluator is most likely to try:

- `VisitPage`: closing a visit, writing a prescription line by line with
  "cheaper brand is OK", a correction starting from the issued prescription
  and posting to the corrections endpoint, and a 409 when the patient
  cancelled meanwhile (the page says so and shows the cancelled visit);
- `VisitsPage`: cancelling only after the patient confirms, no cancel
  button in history, the link to a visit's prescription, and the server's
  reason when a cancellation is refused;
- `SchedulePage`: the "waiting to be closed" flag and the exact
  midnight-to-midnight range asked for when moving a day.

Frontend tests: 78 to 85. Their per-test limit is now 15 s: three long form tests passed alone but crossed the 5 s default when the whole suite ran in parallel. Known gaps gained the doctor sign-up shortcuts.

## 36. Sign in before the doctors list; a quieter navigation

The landing page's navigation carried five destinations and a button: How
it works, Find a doctor, For doctors, For chemists, Sign in, and Book a
visit. Book a visit went to the same place as Find a doctor, and the two
role links duplicated the role switch on the sign-in page and the doctor
and chemist sections further down. They are gone; the island holds How it
works, Find a doctor and Sign in.

Choosing a doctor now needs an account. `/doctors` moved inside the
signed-in routes, so the body guide's answer, a search, a speciality
shortcut or the nav link all go through sign-in first. Two details make
that bearable rather than a wall:

- **The way back keeps the filter.** The route guard stored only the path,
  so `/doctors?specialty=Cardiology&zone=chest` would have come back as the
  unfiltered list. It now stores the path with its query.
- **The sign-in page says why.** Arriving from the doctors list, it reads
  "Sign in to see the Cardiology doctors available and book a time."
  (or names the searched doctor), and "Create an account" carries the same
  destination, so a new patient lands on the list once registered.

The doctor search API stays public: the landing page's search box still
suggests specialities and doctor names before sign-in, and a doctor's
listing is public information, as on Practo. The gate is a product
choice about when to ask for an account, not access control, which is what
the route guard's comment already says of itself.

Tests: a round trip through the real guard and sign-in page, from a
filtered link to the filtered list, and the navigation's contents. Frontend
tests: 85 to 88.

## 37. The body guide takes the visitor's own words

The symptom chips cover what most people have, but not everyone: someone
with a toothache who tapped the head saw headache choices only. The sheet
now has a text box under the chips, "Or say it in your own words", and
typing alone is enough to get an answer.

The text is read with fixed tables, like the rest of the guide, not a
model: the same words always give the same answer, instantly, with nothing
sent anywhere.

- **Warning signs first.** A short list of phrases people actually write
  ("can't breathe", "fainted", "coughing up blood", "spreading to my left
  arm", "worst headache") gives the emergency answer, exactly as a ticked
  warning sign does. The list ignores negation on purpose: a false alarm
  costs a phone call, a missed one costs far more. Writing about ending
  one's life gives the emergency answer with the national mental health
  helpline (Tele MANAS, 14416) added.
- **Then everyday words, whole words only.** The search box's list ("tooth",
  "skin", "heart") names the speciality, matched on word boundaries so
  "ear" is not found in "heart" or "year". A word decides only when nothing
  is ticked: ticks are about the area tapped, and "acid reflux" ticked on
  the stomach should not become a skin doctor because the text also
  mentions an itch.
- **The words go to the doctor, not into the URL.** If the visitor books,
  the booking form's "What brings you in?" starts with what they typed. It
  travels in session storage (this tab only, gone when it closes, cleared
  once booked) because the doctors list's address, which already survives
  the sign-in, would put symptoms into browser history and server logs.

Tests: the phrase list and whole-word matching, the order of precedence,
the sheet with words alone, the helpline, and the booking form picking the
note up and forgetting it. Frontend tests: 88 to 94.

## 38. The next free times, on the doctor's card

Picking a time took two steps: choose a doctor, then open their calendar.
Doctolib puts the next few times on the card itself, and so does the
directory now: three pills per doctor and a "More times" link. Tapping a
pill opens booking with that time already chosen.

- **One query for a page, not one per card.** A card list of 20 doctors
  asking for slots one by one is the classic N+1. `findNextAvailableIds`
  numbers each doctor's open slots with `row_number() OVER (PARTITION BY
  doctor_id ORDER BY starts_at)` and keeps the first three, for all the
  page's doctors at once; the existing `(doctor_id, starts_at)` index
  serves it. It returns ids only, and the slots are then loaded as
  entities: two queries per page, and no guessing which Java type the
  driver uses for a native `timestamptz` column.
- **Only times that can be booked.** The window starts after the booking
  service's 30-minute notice (`BookingService.MIN_LEAD_TIME`, now shared
  rather than copied), and taken slots are excluded by the same rule as
  the calendar.
- **Still a snapshot.** A pill can be taken between the list loading and
  the tap. The booking page checks the chosen id against the fresh list
  and, if it has gone, says so and shows the times still free, rather than
  pretending the old time is available.

Tests: a backend test with a too-soon slot, a taken slot and four free
ones (exactly the three soonest free come back, in order; a doctor with
none gets an empty list), and frontend tests for the pills, the "no free
times" line, the preselected time and the taken-meanwhile message.

## 39. Moving a visit in one step

Changing a visit's time meant cancelling it and booking again, and between
the two the patient held nothing: if the new time went to someone else,
the old one might have gone too. `POST /appointments/{id}/reschedule` does
both in one transaction.

- **Order matters.** The old appointment is cancelled and flushed first,
  then the new slot booked through the ordinary booking path. Cancelling
  first means the patient's own "one visit at a time" constraint never sees
  the two overlap (moving a visit by half an hour works); booking through
  the same path means every rule (verified doctor, 30-minute notice, the
  unique index that settles races) applies unchanged.
- **Both or neither.** If the booking fails, the exception rolls back the
  whole transaction, including the cancellation. A test takes the new
  slot for another patient first and checks the original visit is still
  booked, with no cancellation time.
- **The new row remembers the old** (`rescheduled_from`, V22, unique so a
  visit is moved at most once). That makes a retried request safe without
  an idempotency key: moving an already-moved visit to the same slot
  returns the earlier move. The foreign key is `ON DELETE SET NULL` so
  deleting old rows never trips on it.
- **Same doctor only.** Moving to a different doctor is a different
  decision (a new booking), and is refused with `DIFFERENT_DOCTOR`.
- **One notification to the doctor**, "Meera moved their visit from Wed 30
  Sep, 3:30 pm", instead of a cancellation followed by a booking.

On the page, "Change time" on an upcoming visit opens that doctor's times
in move mode: no reason box, and a single "Move to …" button. A time taken
meanwhile says the visit is unchanged and shows what is still free.

## 40. Diagnosis codes in the prescription writer

A diagnosis was free text only: "GERD", "Acid reflux", "reflux disease"
are one condition written three ways, which no report can count. The
diagnosis box now suggests ICD-10 codes as the doctor types, and a
prescription keeps the chosen code beside the doctor's own words.

- **Codes as reference data** (V23): a table of about 130 common outpatient
  codes from ICD-10-CM, which is in the public domain, each with a few
  everyday keywords the official title lacks ("bp", "sugar", "acidity",
  "piles"). `prescriptions.diagnosis_code` is a nullable foreign key: a
  code is optional, and when present it must be a real one.
- **Search is plain SQL, on purpose.** At this size a scan per keystroke
  costs microseconds, so there is no text index to maintain. Every typed
  word must match the code, the title or a keyword; a code typed as a code
  ranks first, then titles starting with the text. The user's `%` and `_`
  are escaped, so typing them cannot turn into a wildcard.
- **The words stay the doctor's.** Choosing a suggestion fills the box with
  its title, but the doctor can edit the words afterwards or remove the
  code; the patient sees the words, with the code in small print.
- **Validated on the server.** An unknown code is a 422
  (`UNKNOWN_DIAGNOSIS_CODE`), checked before anything is written; lower
  case is accepted and stored upper case.

The search box is a combobox in the ARIA sense (arrows, Enter, Escape,
`aria-activedescendant`), and the list is chosen on mousedown so the
input's blur does not close it first.

## 41. Reviews only from visits that happened

Anyone-can-review ratings are easy to fake. Here a review belongs to a
completed appointment, written by the account that owns it (the patient,
or the family member's account holder), and nothing else can create one.

- **Once per visit, in the schema.** `doctor_reviews.appointment_id` is
  unique (V24). A double submit or two tabs race to the index, and the
  loser gets `409 ALREADY_REVIEWED`; there is no read-then-write check to
  slip past.
- **Aggregated on read, not kept as totals.** The directory asks for the
  average and count of every doctor on the page in one `GROUP BY` query.
  A running total on `doctors` would be faster to read but can drift: the
  demo's nightly reset deletes visits, and their reviews cascade away with
  them, which a total column would not notice. At this scale the index on
  `(doctor_id, created_at)` makes the aggregate cheap; a materialised
  total is the step for when it is not.
- **Privacy.** Reviews are public, like the directory, but name the
  reviewer as a first name and an initial ("Meera N."), never the full name
  or which visit.
- **Portal.** History marks each visit reviewed or not with one query for
  the page (no N+1); completed, unreviewed visits offer "Rate this visit":
  radio-button stars (keyboard and screen-reader usable) and optional words.
  The booking page shows "What patients said".

The demo seeds four reviews and leaves two of Meera's completed visits
unreviewed so a visitor can try it.

## 42. Door 2: walk-in tokens for today

Booking covers "a time next week". It does not cover "I need a doctor this
morning", which in Indian clinics is a token at the front desk. Patients
can now take a token for a doctor today, see their place, and be told when
they are called; the doctor's front desk calls tokens in order.

- **Numbers without duplicates or gaps.** Each doctor's day has a row with
  a counter. A join runs `UPDATE queue_days SET last_token = last_token + 1
  ... RETURNING last_token`, which locks that one row for the increment:
  ten patients joining at the same instant get #101 to #110 (a test does
  exactly that on ten threads). The same statement refuses a closed day,
  and a unique index on (doctor, day, number) is the backstop. If the
  insert then fails because the patient already holds a place (a partial
  unique index on active tokens), the transaction rolls back the increment
  too, so no number is skipped.
- **Calling next is safe with two people at the desk.** `SELECT ... FOR
  UPDATE SKIP LOCKED` picks the lowest waiting token; a second press at the
  same moment skips the locked row and calls the following patient rather
  than calling the same one twice. The patient is notified through the
  outbox, in the same transaction as the call.
- **Place and wait are computed, not stored.** "2 ahead" is a count of
  waiting tokens with lower numbers; the wait is that times the doctor's
  visit length for the day (from their hours, 15 minutes if unset). Nothing
  needs updating when someone ahead leaves.
- **India time.** A clinic's day and its 7 am to 9 pm token hours are in
  Asia/Kolkata; UTC midnight is 5:30 am there. Tests build the service with
  a clock fixed at 10 am India time, so they do not depend on when CI runs.
- **Polling, not push.** The token and desk pages refresh every 10 seconds,
  as notifications already do. Server-sent events would be quicker to
  update but need authentication on a long-lived connection and a proxy
  that keeps it open; ten seconds is well inside how fast a clinic line
  moves.

The demo opens Dr. Menon's line each morning with one seen, one called and
two waiting, so a visitor taking a token gets #105 with two ahead.

## 43. Video visits, browser to browser

A visit can now be booked as a video call, and the patient and doctor meet
in the browser. The design keeps the server out of the call.

- **WebRTC, with the server as a switchboard only.** Two browsers need to
  swap a few messages to find each other: an offer, an answer, and network
  candidates. A small WebSocket handler (`/ws/video`) relays those between
  the two places in a visit's room, patient and doctor, and drops anything
  else. Audio and video then flow directly between the browsers, encrypted
  (DTLS-SRTP is mandatory in WebRTC); the API never sees or stores them.
- **Tickets instead of tokens in the URL.** A browser cannot set headers on
  a WebSocket, and putting the fifteen-minute access token in the query
  string would write it into logs. An authenticated `POST .../video-ticket`
  checks that the caller is this visit's patient (or their family account
  holder) or its doctor, that it is a booked video visit, and that it is
  within 15 minutes before to 30 minutes after its time; it returns 32
  random bytes valid once, for 60 seconds. The handshake redeems it.
- **No glare.** Whoever is in the room first makes the offer when the other
  arrives ("peer-joined"), so the two sides never send offers at once.
  Candidates that arrive before the description they belong to are held
  until it is set. A reload replaces the old connection rather than adding
  a third party to the room.
- **Visit type is a column** (`visit_type`, V26, default `IN_PERSON`), set
  at booking and carried through reschedules; booking's idempotency key
  covers it, so switching to video is a new attempt, not a replay.

The demo seeds a video visit between Meera and Dr. Rao each day and, in
the demo profile only, opens rooms 14 hours either side, so the call can be
tried at any time with two windows.

Tests: tickets (both sides, single use, strangers, in-person, cancelled,
days early), booking as video, and the relay itself with fake sockets
(join notices, offer reaches only the other side, unknown messages
dropped, leaving, rejoining replaces).

## 44. A free option for reading handwriting: Gemini

Reading a photographed prescription needed a paid Anthropic key. Google's
Gemini API has a free tier and reads images, so it is now a second reader.

- **Same interface, same checks.** `GeminiPrescriptionReader` implements the
  existing `PrescriptionReader`. The instructions and the parsing of the
  answer moved into `ReadingParser`, shared by both readers, so switching
  provider cannot change what the model is asked or how its answer is
  validated (JSON only, anything else is a failed read, never half a draft).
- **Chosen by configuration.** `ConfiguredPrescriptionReader` (the primary
  bean) uses whichever readers have a key, Claude first, Gemini second. If
  the first fails (a free-tier rate limit is the likely case) it tries the
  next, and only then falls back to typing. No key: the feature is off, as
  before.
- **Key in a header.** Gemini accepts the key as `?key=` in the URL; it is
  sent as `x-goog-api-key` instead, so it never lands in access logs.
- **Privacy note.** Free-tier requests may be used by Google to improve its
  products. Fine for the demo's sample slips; a deployment reading real
  patients' prescriptions needs a paid tier with the matching terms.

Tests: the Gemini request shape and reply parsing against a mock server,
a rate limit and a non-reading both failing cleanly, and the chooser
(skips readers without a key, falls back after a failure, reports off).

## 45. The body guide's answers reach the doctor

The body guide already passed the patient's own words into the booking
reason. The structured part (which area, the symptoms ticked, since when,
the kind of doctor it suggested) stopped at the patient's screen. Now it
can travel with the booking, and the doctor sees it before the visit.

- **The patient decides.** The booking form shows the answers with "Share
  these answers with the doctor", ticked by default; unticked, nothing is
  sent. They travel in session storage, like the words, because the route
  from the guide to booking passes through sign-in, which drops router
  state, and because symptoms do not belong in URLs.
- **A snapshot, stored as JSON** (`appointments.intake_summary`, V27),
  mapped to a validated record (`Intake`: an area, at most ten symptoms,
  bounded lengths). It is shown as it was and never queried field by
  field, so a column per field would add nothing. A move keeps it.
- **No urgency field.** Answers with warning signs never reach booking:
  the guide sends those patients to emergency care. The doctor's card says
  so ("no warning signs were reported") rather than inventing a triage
  level.
- **Not copied into the prescription.** The spec suggested importing the
  answers into the visit note; the only note on a prescription is the one
  for the patient, so importing would hand the patient their own answers
  back. A private clinical note is the right home, and does not exist yet.

Tests: the intake reaches the doctor's visit view and survives a move;
no intake when none is sent; an empty area is refused; the booking form
sends it only while ticked and forgets it once booked.

## 46. A waiting list for a full day

When the day a patient wants is full, they can now ask to be told if a
time opens. A cancellation or a move that day notifies everyone waiting.

- **One row per doctor, patient and day** (V28, unique). Joining again
  after leaving is an upsert back to ACTIVE, not a second row. Patients,
  not accounts: Meera can wait for her mother's appointment, and the
  notification says so ("For Lalitha, a visit on Wed 30 Sep...").
- **Told in the same transaction as the cancellation.** `slotReleased`
  runs inside the cancel (or the move) with propagation MANDATORY, and
  claims everyone waiting in one `UPDATE ... RETURNING`, publishing an
  outbox event for each. If the cancellation rolls back, the events roll
  back with it: nobody hears of a time that never opened. A too-soon slot
  (inside the 30-minute notice) is not announced, since nobody could book
  it.
- **Everyone is told; the first to book wins.** No reservation is held for
  the first person on the list: holding a slot for someone who may never
  open the notification wastes it. The existing unique index on active
  appointments decides between those who try.
- **The wait ends by itself.** Booking that doctor on that day marks the
  entry fulfilled, in the booking's transaction. Days are India time.

The spec's BIGINT ids and user-level rows were adapted to the schema's
UUID keys and to patients, so family members work.

## 47. Insurance and prices before booking

Patients can now filter the directory by the insurance a clinic accepts and
see what a visit costs before they book.

- **Insurers are reference data** (V29 `insurers`): Star Health, Care
  Health, HDFC ERGO, ICICI Lombard, Niva Bupa, New India Assurance, CGHS and
  Ayushman Bharat (PM-JAY). `doctor_insurance` references it, so a filter
  value is always real and a typo cannot invent an insurer.
- **The filter is one EXISTS in the existing search.** A small read-only
  entity (`DoctorInsurance`) lets the JPQL query say `EXISTS (SELECT 1 FROM
  DoctorInsurance i WHERE ...)`, with the same `cast(:param as String)`
  guard every optional parameter there needs (entry on the bytea bug).
- **Still no N+1.** Insurers and prices for a page come in one query each,
  beside the next free times and the ratings: a page of 20 doctors costs
  the same few queries as a page of one.
- **What a visit costs.** The consultation fee stays on `doctors`; extra
  charges live in `doctor_procedure_prices`. Charges marked "every visit"
  (a registration fee) are added to the fee as "Each visit", so the number
  a patient sees first is the number they pay.
- **Adapted from the spec**: UUID keys, prices beside the fee rather than a
  second "Consultation" row, and an insurer list for India. What it is not:
  an eligibility check. Accepting a scheme is the clinic's statement.

## 48. Free follow-up questions after a visit

For seven days after a visit ends, the patient can ask the doctor up to
three questions, and the doctor answers from the visit page. Each side is
notified through the outbox.

- **The limit holds under concurrency.** Counting three questions and then
  inserting is the classic check-then-act race: two questions sent at once
  both count two and both insert. Posting first locks the visit's row
  (`SELECT ... FOR UPDATE`), so the second waits, then counts three and is
  refused. A test sends five at the same instant: exactly three are taken.
- **Order is an identity column.** A question and its answer can share a
  timestamp; sorting ties by a random UUID could show the answer first.
  `seq GENERATED ALWAYS AS IDENTITY` orders the thread as written (the same
  lesson as the sign-in codes, entry 32).
- **No stranded questions.** The seven days limit the patient; the doctor
  can answer whatever is waiting even after they end, and can post only when
  a question is waiting, so the thread stays question-and-answer.
- **Who.** Only this visit's patient (or the family account holder) and its
  doctor can read or write; the server decides the side from the caller.

## 49. Records attached before a visit

Patients can attach lab reports and earlier prescriptions to an upcoming
visit, right after booking or from the visit, and the doctor reads them on
the visit page before the patient walks in.

- **The type comes from the bytes.** The browser's Content-Type and the file
  name are claims; the server reads the first bytes (`%PDF-`, JPEG, PNG,
  WebP signatures) and refuses anything else, so a script renamed
  "report.pdf" is a 422. The stored type is the sniffed one, the name is
  cleaned (no path, safe characters, the extension of the real type), and
  the file is served with `X-Content-Type-Options: nosniff`, `no-store` and
  an inline disposition.
- **Who.** The visit's patient (or the family account holder) uploads and
  removes, while the visit is still to come; the patient and the visit's
  doctor read; a doctor opening a file is written to the audit log, as any
  access to a patient's record is. Another doctor gets 403.
- **Limits under concurrency.** Five files per visit, counted with the
  visit's row locked, the same pattern as the follow-up questions.
- **Stored like the prescription photos** (BYTEA, V31), with the size capped
  by a CHECK as well as by the upload limit. Object storage is the step
  after this; see Known gaps.

## 50. Is the doctor running late?

On the day of an in-clinic visit, the patient sees "Doctor is on time" or
"running about 20 min behind", refreshed each minute, so they can time
their arrival.

- **From what the system actually knows.** Medicity does not record when a
  consultation starts, only when the doctor closes a visit as seen or
  missed. So the estimate is: the oldest visit today that is still open
  after its slot ended; the doctor is behind by how long ago it should have
  ended. A visit still inside its own slot is "in progress", not late.
- **Forgotten visits are not delays.** A visit that ended more than 90
  minutes ago and was never closed is taken as forgotten (the nightly job
  closes those as missed). Without that rule, one forgotten morning visit
  would make the doctor look hours late all afternoon.
- **Ten minutes is on time.** Clinics drift; under ten minutes reads as on
  time rather than alarming anyone.
- **Signed-in users only.** It says something about a clinic's day that the
  public directory does not need, so a filter rule requires sign-in (a
  method-level check alone would answer 403, not 401, after the directory's
  public rule).
- **Named honestly.** The pill's tooltip says it is an estimate from
  unfinished visits; the spec's "based on current consultation progress"
  would promise data the system does not have.

## 51. The family and open-question caps, locked

Two limits were checked, not locked: an account manages at most eight family
members, and a patient has at most five open questions to the chemists. Each
counted, then inserted, so two requests at the same moment could both see
seven members (or four questions) and both pass. Known gaps called this out.

Both now lock a row first, the pattern follow-ups and attachments already use:

- **Family:** `SELECT id FROM users WHERE id = ? FOR UPDATE` on the account
  holder, then count, then insert. A second add waits on the lock and counts
  after the first has committed.
- **Questions:** the same on the patient's row before the open count.

The lock is per account, so it only makes one person's own simultaneous
requests wait for each other; nobody else is slowed down. An advisory lock
would have worked too, but a row lock needs no key scheme and is released by
the transaction like everything else.

**Verified by.** `FamilyTest`: at six members, five adds sent at once give
exactly two 201s and eight members. `MedicineRequestTest`: seven questions
sent at once with none open give exactly five.
## 52. Reserved medicines leave the shelf

A store with live stock answered "yes, 10" to every question until its billing
software sent the next list, even after reservations had taken all ten
(listed in Known gaps). Now automatic answers use what is left.

**Computed, not written.** The obvious fix is to decrement `store_stock` on
reserve and add it back on cancel or expiry. That goes wrong the moment the
store sends a new list while a reservation is held: billing software still
counts those medicines as on the shelf, because nobody has bought them, so the
new list silently undoes the decrement. Instead, the quantity an answer sees is

    listed quantity
    − medicines in reservations still HELD
    − medicines in reservations COLLECTED after the list was sent

A held reservation always counts, whenever the list was sent. A collection
counts only until the next list, which already reflects the sale. A cancelled
or expired reservation stops counting with nothing to undo. It is one
`LEFT JOIN` on an aggregate over the store's reservations, using the existing
`idx_reservations_store_held` index, and it runs only when a store answers
automatically.

**Verified by.** `StoreStockAndInsightsTest`: with exactly one prescription's
worth in stock, the first question hears yes; after Meera reserves, the next
hears no; after she cancels, yes; after a reservation is collected, no; after
the store sends its list again, yes.

## 53. Query plans at scale, checked in CI

On demo data every query is fast, because PostgreSQL rightly reads a
few-dozen-row table whole. That says nothing about a clinic network a year
in. `QueryPlanTest` seeds a realistic volume inside one rolled-back
transaction (200 doctors, 120,000 slots, 60,000 visits, 10,000 patients,
100,000 notifications, 50,000 outbox events), runs `ANALYZE`, then
`EXPLAIN ANALYZE`s the eleven hottest queries the way the application sends
them. A sequential scan of any large table fails the build, and the table of
timings and full plans goes to the CI job summary.

It found two queries that read a whole table:

| Query | Before | After | Fix |
|---|---:|---:|---|
| Directory: next three free slots for a page of 20 doctors | 17.9 ms, all 60,000 visits read | 0.5 ms | `LATERAL … LIMIT 3` per doctor instead of a window function |
| Hourly job: visits never closed | 28.5 ms, all visits and all slots read | 13.8 ms, reading only the 600 seeded never-closed visits | Partial index `(scheduled_at) WHERE status = 'BOOKED'` (V32) and a per-candidate slot lookup |

- **Why the directory query degraded.** The window function numbered every
  open slot in the next two weeks for all 20 doctors (about 6,000), then
  checked each against appointments. For that many probes PostgreSQL
  preferred hashing the whole appointments table, which is fine at 60 rows
  and grows with every visit ever booked. With `CROSS JOIN LATERAL (…
  ORDER BY starts_at LIMIT 3)`, each doctor's slots are walked in index order
  and the walk stops at the third free one: about 60 probes a page,
  whatever the table size.
- **Why the job did.** It filtered on the slot's end time, which no index on
  appointments can serve. Only a handful of rows are ever BOOKED and in the
  past (the job keeps it so), so a partial index on exactly those is tiny.
  The job adds `scheduled_at < cutoff`, which is implied by the slot ending
  before the cutoff, so the index can be used. The slot's end then becomes
  a scalar sub-select, so the planner looks up each candidate's slot by key
  rather than hashing all 120,000.
- **The doctor's schedule** filtered and sorted on the appointment's copy of
  the start time; it now uses the slot's, which the `(doctor_id, starts_at)`
  index already serves. The two are always equal.

The job's time now grows with the visits it has to close (in production, the
last hour's), not with every visit ever booked.

Everything else was already index-served: patient visit lists, the day's
schedule, "running late", notifications, the outbox poll. Each now has a
test that says so.

## 54. The walk-in queue and the directory under load

The k6 run (entry 12) covered booking only. It now also covers what came
after it: the walk-in queue and the doctor directory.

- **300 patients take a token at one doctor at the same moment.** Every one
  gets a token, numbered #101 to #400 with no gap and no repeat. The p95
  was 590 ms: all 300 wait in turn on one row, the day's counter (`UPDATE
  queue_days SET last_token = last_token + 1 RETURNING …`), which is
  exactly what makes the numbering gap-free. Real queues see a few joins a
  minute, not 300 at once, so this is a worst case and not a target.
- **8 front-desk tabs press "Call next" together until nobody is left.**
  300 calls, each token called exactly once, p95 14 ms. `FOR UPDATE SKIP
  LOCKED` hands each tab a different patient instead of making the tabs
  queue behind each other.
- **Reads now include the directory page** (next free slots, ratings,
  insurers and prices for 20 doctors, one query each) and a name search,
  alongside a doctor's slots and "my visits".
- **`verify.sql` checks the queue too:** tokens run from 101 to 100 + n with
  no gap or repeat, and no token is left waiting.

One run on a shared 4-vCPU GitHub runner, with the API, PostgreSQL and k6 on
the same machine: 2,421 bookings, 20 of 20 contended slots booked once, 180
clean 409s, no double booking. Booking p95 15 ms, read p95 7 ms, no failed
requests.

The first run failed the new "contiguous tokens" check. The code was right
and the check was wrong: numbering starts at 101 (the counter's default is
100, so tokens read like a clinic's), and the check had assumed 1.

## 55. Each page loads when it is first opened

The web app was one 466 KB script (139 KB gzipped): a patient opening the
landing page downloaded the doctor's prescription writer, the chemist's stock
screen, the admin queues and the video room too. Now only the landing and
sign-in pages are in the main bundle; every other page is its own chunk,
fetched the first time it is opened.

| | Before | After |
|---|---:|---:|
| Main script | 466 KB (139 KB gzipped) | 334 KB (104 KB gzipped) |
| Largest page chunk | n/a | Visit page, 14 KB (5 KB gzipped) |

- **`lazyPage(() => import("./pages/X"), "X")`.** Pages are named exports
  and `React.lazy` wants a default one, so a small helper picks the export
  by name and keeps the pages as they were.
- **The layouts stay in the main bundle and hold the `<Suspense>`.** The
  portal, doctor and store sidebars do not blink while a page loads; only the
  content area waits.
- **Navigation is a transition** (`v7_startTransition`). While the next
  page's code downloads, the current page stays on screen instead of a
  "Loading…" message; the message appears only on a cold first load.
- **A shared constant was pulling a page in.** `NOTIFICATIONS_KEY` lived in
  `NotificationsPage`, and the header's bell imports it, which would have
  kept that page in the main bundle. It moved to `lib/notifications.ts`.

What remains in the main script is mostly React, React DOM, the router,
TanStack Query and the landing page itself.

## 56. Sessions and days off

Hours were one window per weekday, so a doctor with a lunch break, or a
morning clinic and an evening one, could not say so, and there was no way to
take a day off short of deleting that weekday (listed in Known gaps).

- **Up to three sessions a day.** V33 re-keys `doctor_hours` from (doctor,
  weekday) to (doctor, weekday, start). The service sorts each day's
  sessions and refuses overlaps (`SESSIONS_OVERLAP`) and a fourth session
  (`TOO_MANY_SESSIONS`). Slot generation already looped over windows, so
  a Monday of 09:00–11:00 and 14:00–15:00 at 30 minutes simply opens six
  slots and none in the gap. The screen adds "+ Session", starting an hour
  after the previous one ends.
- **Days off.** `doctor_leave (doctor_id, day)`. Marking a day removes its
  open slots that nobody booked, and the nightly top-up and any later
  save skip it. The walk-in queue says "The doctor is not in today" and gives
  no tokens. Removing the day reopens its slots.
- **Booked visits are kept, and counted.** A day off does not cancel
  anyone's visit by itself: which patients to call, move or see anyway is
  the doctor's call. The answer and the list both say "2 visits are still
  booked that day", in red.
- **A query that assumed one row per day.** The queue's minutes-per-patient
  estimate read `slot_minutes` with a scalar sub-select on (doctor,
  weekday), which would raise "more than one row returned" the first time a
  doctor saved two sessions. It now takes the shortest session's length.
  Only a search for every reader of `doctor_hours` found it; no test would
  have failed until a doctor used the feature.

**Verified by.** `DoctorSignUpTest`: two sessions open exactly the six
expected times; overlaps and a fourth session are refused; a day off removes
three open slots and keeps the booked one, survives the nightly top-up, and
reopens three when removed; a past day is refused. `QueueTest`: on a leave
day the status says so and joining is refused. Frontend: the second session
defaults to after lunch and is sent; days off list their booked visits.

## 57. Doctors keep their fee, insurers and prices current

The fee and bio were fixed at sign-up, and insurers and prices existed only
in the demo seed (entry 44), so a real doctor could not list either. "Fees
and insurance" (`/doctor/practice`, `GET`/`PUT /api/v1/doctors/me/practice`)
edits all of it on one screen: fee, years in practice, bio, insurers from
Medicity's list (grouped private, public sector, government schemes), and up
to 20 other charges, each marked "every visit" or not.

- **Replaced whole, like hours and stock.** The form shows everything, so it
  sends everything back; a charge left out is withdrawn, an insurer unticked
  stops finding the doctor under that filter. There is no per-line
  add/remove API to get out of step with the screen.
- **Validated against what exists.** An insurer not in the `insurers` table is
  `UNKNOWN_INSURER` (the directory filter is an exact match, so a typo would
  silently hide the doctor). Procedure names are compared ignoring case and
  spacing, so "ECG" and " ecg" are `DUPLICATE_PROCEDURE`, not two lines.
- **Unnamed rows are dropped, not refused.** An empty "+ Add a charge" row is
  an unfinished thought, not an error worth a red message.
- **Visits already booked are not affected.** Medicity shows the fee in the
  directory and the booking page; it does not store or charge it per visit,
  so there is nothing to rewrite. The change is audited
  (`DOCTOR_PRACTICE_UPDATED`, old and new fee).

**Verified by.** `DoctorPracticeTest`: saving shows in the directory's
insurance filter, fee, years and "every visit" ordering at once; a later save
without an insurer or price withdraws it; an unknown insurer, a duplicate
procedure and a negative fee are refused with nothing changed; patients get
403 and the signed-out 401. Frontend: the form sends the whole practice and
drops unnamed charges; a refusal shows the server's reason.

## 58. The first screen of the README, and a Lighthouse check

A reviewer gives a repository about a minute and a half. The README opened
with the concurrency argument, which is the point, but a reader had to scroll
past the architecture to find the live link, and further to find a demo
login. The first screen now carries the live app and API reference, a
screenshot, the five demo accounts with what to try as each, and a table of
measured numbers, each traceable to a test or a load-test run in this log.
The architecture diagram, drawn before the outbox, the scheduled jobs, the
video relay and the prescription readers existed, now shows them.

**Lighthouse**, run by a new workflow (weekly, on demand, and on changes to
itself) against the live landing page, because a score measured on a local
build says nothing about Vercel's edge:

| Profile | Performance | Accessibility | Best practices | SEO |
|---|---:|---:|---:|---:|
| Desktop | 100 | 96 | 100 | 91 |
| Mobile (emulated slow phone) | 63 and 91 in two runs | 96 | 100 | 91 |

The mobile score moved 28 points between two runs minutes apart, all of it
total blocking time (2 s, then 0). The shared runner's CPU explains that, not
the page, so the README quotes the range and not the better number. The
workflow now prints where the phone profile spends main-thread time, so a
real regression can be told apart from noise. The one change made on the
evidence was loading Google Fonts without blocking the first paint
(`preload` swapped to a stylesheet on load, with a `<noscript>` fallback).

## 59. The workspaces join the line

The landing and sign-in pages had an identity (enamel ground, one line
colour per role, station roundels) and the workspaces behind sign-in did
not: a system font, one teal for every role, and the admin template of a
grey link list over a grid of equal cards. They now share one world.

- **One line colour per role, by one attribute.** `data-role` on the app
  root repaints every accent: patient teal, doctor indigo, chemist
  marigold. Each role has three tones, because one colour cannot do three
  jobs: `--accent` is a band that carries text, `--line` is a stroke, and
  `--accent-text` is the tone readable as type on the ground. Marigold is
  the reason: it is unreadable as text on white and cannot carry white
  text, so 28 uses of the accent as a text colour and 19 as a stroke moved
  to the tone made for the job.
- **The navigation is the line.** A rail with a band and a station per
  section; the station being visited is ringed in the line colour. On a
  phone the rail becomes a top bar with the line along its foot. The top
  bar is gone inside the workspaces and kept for the pages between them.
- **Each role opens to its own form, not to cards.** The patient's next
  visit is a ticket (mono date stub, tear line, one action), followed by
  figures on one ruled row. The doctor's day is a vertical line of visits:
  a station per visit, ringed while it waits to be closed, filled once
  seen. A chemist's question is a ruled row with the distance as a mono
  stub. Lists are ruled rows on the ground.
- **Drawn icons.** Emoji standing in for icons (the status dot, the
  paperclip, the speech bubble) and text arrows were replaced with the icon
  set the landing page already uses.
- **Scoped so the public pages cannot change.** The base rules sit under
  `:where(.in)`, a class the app root carries everywhere except the landing
  and sign-in pages, at zero specificity so component rules still win.

**Seeing signed-in screens without signing in.** `npm run preview:ui`
serves the app against recorded answers from the public demo (no tokens or
passwords in the fixtures) with `?as=patient|doctor|chemist`. It exists
because a redesign has to be looked at, and needs no backend; the branch is
statically removed from production builds, which a check of the built
bundle confirms.

Behaviour, copy and routes are unchanged: all 126 frontend tests pass
untouched. Checked at desktop and phone width in both themes.

## 60. A premium pass: elevation, rhythm and home screens that lead

A review of the signed-in screens against the products people hold up as
the bar (Linear, Apple Health, One Medical) found a consistent set of
weaknesses, all of them structural rather than cosmetic.

- **No elevation ladder.** Every box was a flat bordered card with the same
  weight, so nothing led. There are now four steps and nothing between:
  the ground is flat, a panel or tile rests on it (`--e1`), what is under a
  pointer lifts (`--e2`), the ticket floats (`--shadow`), a menu covers
  (`--e4`). The diagnosis menu moved onto the top step.
- **Type without tension.** Headings shared one letter-spacing whatever
  their size. They now tighten as they grow, mono figures tighten further,
  and paragraphs use `text-wrap: pretty` so a line never ends on one word.
  Table headers dropped to 12px with more room beneath, rows gained a
  hover tint.
- **A home screen was a list of equal things.** Each role now opens to one
  lead item and the figures around it on a twelve column grid: the patient
  sees the ticket, three shortcuts to the three jobs (book, ask the
  chemists, find a store) and what they are taking, with a warning tile when
  a course runs out; the doctor sees the visit to act on (the oldest still
  open, else the next one) with still-to-come, to-close and seen counts;
  the chemist sees questions waiting, reservations to keep aside and the
  median minutes to answer, each linking to its page. The lead tile takes
  the role's tint, so the one thing to act on is the only tinted thing.
- **Wasted space.** The content column was pinned against the rail on wide
  screens; it is now centred with more air (48px gutters, 32px between
  sections). Lists sit in one panel with a head and ruled rows instead of
  a card per row; detail lists got ruled rows.
- **A bug the tint exposed.** A custom property is resolved where it is
  declared, so `--accent-soft` computed at the root stayed teal inside the
  doctor and chemist roles. It is restated per role.

Adapted rather than copied from the brief: the stack stays React and plain
CSS, and the type stays Manrope with Geist Mono for exact values, because
the system already carries an identity (one line colour per role) that a
generic blue and white would erase. "Booking a hospital" is not a Medicity
flow, so nothing was invented for it.

No behaviour, route or copy changes; the schedule test now expects the
lead tile and the line to both name the visit. All 126 frontend tests pass.
Checked at desktop and phone width.

## 61. The landing page: a capsule that turns, matching doors, a map of where you are

Feedback after walking the live landing page: the navigation repeated what
the page already said, the two doors did not line up, the map named one
neighbourhood and one person, and two "try the demo" links and a section of
role descriptions added noise to a page whose job is to start a search.

- **One capsule, one way in.** The "Find a doctor" and "How it works" links
  are gone. The floating capsule is larger and holds the brand, the two
  lines the page used to open with turning one after the other ("Find the
  right doctor, then your medicines nearby." then "From prescription to
  medicines in hand, near you."), and a single Sign in button (Open my
  account once signed in). It turns every five seconds, holds still while a
  pointer or the keyboard is on it, does not turn at all under reduced
  motion, and each page has a station dot to choose it. Both lines stay in
  the page as headings for screen readers. The phone menu and burger went
  with the links: there was nothing left to put in them, so on a phone the
  capsule becomes two rows.
- **Matching doors.** The grid was 1.1 : 1 with both doors top aligned, so
  the shorter search door ended well above the body guide. The columns are
  equal, the doors stretch, the ledes share a minimum height so the content
  starts on one line, and the speciality tiles take whatever height is left,
  so both doors end together.
- **A map that is about the viewer.** The label "Meera, Indiranagar" and the
  note "Demo data from Indiranagar" are gone. Signed out it is labelled as
  an example. Signed in, as a patient, doctor or chemist alike, it asks the
  browser for the person's position once when the map is first seen and
  draws the verified chemists within 3 km of it from the same directory the
  app searches (any signed-in role may read it). No answers or prices are
  drawn for real stores, because nobody has asked them anything, so it says
  open now or closed now instead. If location is refused it falls back to
  the example and says how to allow it. The position is not stored or sent
  anywhere but the nearby search.
- **Removed.** Both "Try the demo" links, and the "Three lines, one
  prescription" section, whose sign-in and registration links now live in
  the footer (with "Join as a doctor", which only that section carried).

Decision worth recording: the permission prompt appears when a signed-in
person scrolls to the map, not on page load and not behind a button. They
have signed in, so a location question next to a map of nearby chemists is
expected, and a button would have meant the map stays generic for most
visitors.

Six new tests (the map for each role, refusal, signed out, the capsule, the
removals); all 132 frontend tests pass.

## 62. Two bugs found by using it: sign-in landing on the old page, and the family switch

Both were reported after a person actually used the portal.

- **Signing in opened the page that was open when the last sign-out
  happened.** Signing out from "Walk-in tokens" and back in landed on
  "Walk-in tokens". Cause: `logout()` cleared the session first, so the
  protected page re-rendered signed out and redirected to `/login` with
  itself remembered as the place to return to (a feature for deep links,
  such as "book this doctor" sent to sign in first); the `navigate("/")`
  that followed was too late to matter. Both sign-out buttons now leave
  first and clear the session second, so nothing is remembered. A genuine
  redirect to sign in, from a link or an expired session, still returns
  the person where they were headed. A test signs out from a protected page
  with the real provider and checks the sign-in page is never shown.
- **Switching to a family member kept showing the account holder.** The
  switcher removed the previous person's cached answers, but a page already
  on screen kept its data until it was reopened (so Overview showed Meera
  under Lalitha's name until clicked), and the greeting read the signed-in
  account's name, never the chosen person's. Switching now resets the
  cache (mounted pages refetch for the new person at once), opens that
  person's overview, and the greeting and its subtitle use their name.
  The test switches person from the visits page and checks the overview,
  the greeting, and the `X-Patient-Id` header on the refetch.

## 63. What a clinic asks: a health profile and a saved location

Signing up asked for a name, an email, a password and a date of birth, and
nothing a clinic asks before it sees someone (blood group, allergies,
long-term conditions, medicines taken now), and never asked where the person
is, so nothing could be measured against it later.

- **Sign-up has a second step.** After the account exists, `/welcome` asks
  for blood group, height, weight, allergies, long-term conditions, current
  medicines, an address, an emergency contact, and offers to save the
  person's current location. Every question is optional and the whole step
  can be skipped. It sits after account creation, not inside the sign-up
  form, so a long form never stands between a person and an account, and a
  failed health field cannot lose the account. Gender, which the API already
  accepted but the form never asked, is now on the sign-up form.
- **One form, two places.** The same `HealthForm` is on the profile page
  behind "Edit health and location", so the answers can be completed or
  corrected later, and for a family member when their portal is open (the
  route acts for whoever is being viewed, as the rest of the portal does).
- **The location is a choice, and it is stored as a choice.** It is asked
  for with a button, not on page load, it can be removed, and the browser's
  answer is saved as two numbers on the patient row (migration V34, with
  both-or-neither and range checks in the database as well as the API), not
  as a trail. Distances to clinics and chemists are worked out from it.
- **`PUT /api/v1/patients/me` replaces the whole record.** The form shows
  every field, so the body is all of it and an omitted field is cleared;
  half a location is refused (`LOCATION_INCOMPLETE`), as is an impossible
  height. A doctor's token gets 403, and a test checks another patient's
  record is untouched. Health text is free text and never parsed, as a nurse
  would write it down.

Two backend tests (update and replace; refusals) and two frontend tests
(the form sends the whole body with the saved location; skipping sends
nothing).

## 64. Find a doctor and booking, rebuilt around how far, how much and when

The directory showed a card per doctor with the next three times as pills
reading "Tomorrow, 3:30 pm", "Tomorrow, 4:00 pm", "Tomorrow, 4:30 pm", and a
"More times" link to a page that listed every open time in a fortnight as one
long wall of buttons. Nothing said where the clinic was or how far.

- **Where the clinic is.** Doctors have a clinic name, address and a pin
  (migration V35, both coordinates or neither, checked in the database as
  well as the API). A doctor sets it on the Fees and insurance page, with a
  button that uses the browser's position (best pressed at the clinic). The
  demo doctors have clinics around Indiranagar. Practice, like hours and
  prices, is replaced whole, so leaving the clinic out clears it.
- **How far.** The app works the distance out itself (haversine, straight
  line, and labelled "away", never "drive") from the person's saved location
  when they have one, else from the browser when they press "Use my
  location", never on arrival. Each clinic has a Directions link that opens
  the person's maps app. "Nearest first" sorts what is on the page, and a
  doctor with no clinic location goes last rather than being hidden.
  The server returns coordinates and does no geometry, so there is nothing
  to keep in step and no location ever reaches it from a search.
- **When, in rows and steps.** A card's next times are one row per day ("Tomorrow  10:00 am  10:30 am")
  instead of repeating the day in every pill, and the link is "See all
  times". Booking is three numbered steps: a strip of the days that have
  open times (with how many), the chosen day's times grouped into morning,
  afternoon and evening, and a summary that stays in view with the visit
  type, the reason and one Confirm. Days with nothing are not shown, so
  nobody scans empty days. The page names the doctor, the clinic, the
  distance and the whole fee for a visit, so nothing is learnt only after
  confirming. `GET /api/v1/doctors/{id}` (listed doctors only; an unverified
  one is a 404) feeds that header.
- **Unchanged on purpose.** The idempotency key, the lost race handling,
  the waiting list and moving a visit keep their logic and their tests; the
  time buttons keep `aria-pressed` and a full-date accessible name, and the
  day strip is a tab list so the two never read as the same kind of control.

One backend test (set, read in the directory and by id, half a location
refused, cleared by omission, 404) and three frontend (distance and sort,
the day/part-of-day layout, the practice form's clinic).

## 65. A 3D landing page was tried and dropped

A 3D direction (a scroll journey along the line, a body to turn, a map of
chemists, in Three.js) was built as a hidden preview, looked at in a real
browser, and then dropped by the product owner in favour of a 2D page that is
clean and strikes quickly. It was merged and then removed within a day, and
nothing on the live pages ever used it.

What it taught, for the 2D redesign that followed:

- The three scenes worked and loaded lazily, but cost about 270 KB gzipped
  on top of a 106 KB app: a poor trade for a first impression that has to land
  in seconds on a mid-range phone.
- The thing that read best in 3D was not the 3D: it was the idea of one line
  with five stations, and a question going out and answers coming back. Those
  are as strong flat, drawn big.
- A hero needs one focal point, not a technique. The page's problem was never
  the number of dimensions.

## 66. A bolder 2D landing page, as a sample

After dropping 3D, the question was where the landing page actually falls
short. Reading the live page as a visitor does, the first screen was the
problem: two equal white tool panels (a grey body and six empty tiles) with no
focal point, the headline living in a small capsule, 14px grey type on
cream, one colour used sparingly, and the best ideas (the line with five
stations, the question going out and answers coming back) either below the
fold or drawn small and pale. Nothing said what Medicity is, why it matters,
or what to do first.

The sample at `/lab/design` (not linked, not indexed, the live landing page
untouched) keeps the Enamel Signage world and changes the composition:

- **One promise, one action, one picture.** "Find your doctor. Then your
  medicines nearby." at 64px, the real search box drawn as the main action
  (64px high, ink outline, one deep shadow), six speciality chips under it,
  and the body guide as a clear second door. Beside it the product in three
  real fragments (a visit ticket, a chemist's answer with its price, a
  pick-up code) strung on the line, which draws itself once on load.
- **Colour in blocks.** The line section is a full-width ink band with the
  five stations on a thick teal line; the page closes on a full-width teal
  band with one white button. Light, dark, light, grey, teal: the page has a
  rhythm that scrolling can feel, which the cream-on-cream page did not.
- **The map earns its size.** The existing map is shown larger in a white
  panel with its headline beside it; its route-in line is hidden here because
  it would run through the headline.
- **Not repeated.** The three role sections the owner asked to remove stay
  removed; a hero-metrics row and section numbers were not added.

Also found while checking at phone width: the capsule's rotating line takes a
quarter of a phone's first screen, so on a phone (sample only) the capsule is
one row and the page's own headline carries the message; and absolutely
positioned fragments overlap on a phone, so they stack in the page flow there.

One authored motion (the line drawing, then the three fragments rising in
turn), none under reduced motion. One render test. The sample reuses the
real search box, body guide and map, so adopting it is a swap of the
landing page's composition, not a rebuild.

## 67. A white background everywhere

The product owner asked for a white background across the whole site. The
ground was a warm off white (`#f7f7f2`) in two token sets, one for the landing
and sign-in pages (`--lm-ground`) and one for everything behind sign-in
(`--bg`); both are now `#ffffff`, so the public pages, the three workspaces
and the pages between them agree. DESIGN.md names the ground as white.

Two things follow from putting white panels on a white ground, and were
handled rather than left to chance:

- Panels were separated from the page by a tone; now they are separated by
  their 1px rule and their shadow, which they already carried, so they still
  read as panels in the screenshots checked (landing, patient overview,
  directory).
- Four small insets (the prescription note, the "how to take" block, the
  cancelled visit's date tile and the sample card's figures) had used the
  ground as their tint on a white card. On a white ground they would have
  vanished, so they use the recessed `--raised` tone instead.

The night theme is untouched: it still follows the device setting, so a
phone set to dark mode keeps a dark page. Only the light page changed.

## Known gaps (tracked, not hidden)

- **Doctor verification is a manual look-up.** The administrator checks the
  registration number against the council's register by hand; there is no
  certificate upload and no automatic check (the National Medical
  Commission's register has no public API).
- **A day off does not tell booked patients.** Marking leave keeps visits
  already booked and shows the doctor how many; contacting those patients,
  or cancelling, is left to the doctor.
- **Video calls use STUN only, and the relay is in one process.** Two
  browsers behind strict firewalls or carrier-grade NAT may fail to connect
  directly; that needs a TURN server (relayed media), which costs money to
  run. The signalling rooms and tickets live in the API's memory, so a
  second API instance would need them shared (Redis pub/sub). The call is
  not recorded, and there is no in-call chat or screen sharing.
- **The walk-in queue refreshes by polling, and has no receptionist role.**
  Places update every 10 seconds rather than being pushed; the doctor's
  account is the front desk, and there is no separate kiosk or receptionist
  login. Arrivals for booked visits are listed beside the walk-ins, not
  merged into one numbered line.
- **"Running late" is an estimate.** It relies on doctors closing each visit
  as they finish; a doctor who closes them all at the end of the day looks
  late until then. There is no check-in or "consultation started" event.
- **Attached records live in the database.** Like the prescription photos,
  bounded (5 MB, 5 per visit) but not what object storage with signed URLs
  would give at scale; there is no virus scan.
- **Insurance is what the clinic says it accepts.** There is no policy or
  eligibility check with the insurer, and a doctor can only choose from
  Medicity's list of insurers, not add one.
- **ICD-10 is a curated subset.** About 130 common outpatient codes from
  ICD-10-CM (public domain), not the full classification; a condition
  outside it can still be written in words, just without a code. Loading
  the full CMS release would be a data import, not a schema change.
- **Typed descriptions are read by word lists, in English only.** The body
  guide's text box looks for fixed warning phrases and everyday words; it
  ignores negation ("no crushing pain" still sends people to emergency care,
  deliberately), misses misspellings, and does not read Hindi or other
  languages.
- **The body guide has not been reviewed by a clinician.** Which doctor each
  area and symptom points to, and which symptoms count as emergencies, were
  written from general medical knowledge. It errs towards emergency care and
  says it is not a diagnosis, but it needs a doctor's review before real
  patients rely on it.
- **Codes are not texted yet.** No SMS provider is configured, so only the
  demo accounts can sign in with a code (shown on screen); real numbers are
  told to use email. The MSG91 sender has only been tested against a mock.
  "Send a code" also takes slightly longer for a number with an account
  (one insert), a timing difference far smaller than network jitter but not
  zero.
- **Prescription photos are stored in PostgreSQL.** `BYTEA`, capped at 5 MB by
  a CHECK. Fine at demo scale; at volume they belong in object storage with
  the database holding a key, so backups and replicas stay small.
- **A question keeps the patient's location.** The latitude and longitude it
  was asked from stay on the row for distance and insights. They are never
  shown to a store (stores see a distance) but are not coarsened or deleted
  after the question closes.
- **Chemist sign-up is not rate-limited either,** for the same reason as
  patient registration below. A store is inert until an administrator checks
  its licence, which limits the harm.
- **An automatic answer trusts the store's stock list completely.** There is
  no check that the prices are plausible or that the list came from billing
  software rather than being typed in.
- **Medicine instructions in Indian languages are unreviewed.** The phrasebook
  behind "How to take" (entry 27) was written without a native speaker or a
  pharmacist checking it. It is deliberately small and falls back to the
  doctor's English for anything it does not recognise, but each phrase needs
  review before real patients rely on it.
- **Audit IP addresses are Railway's edge proxies, not clients.** Found when
  checking the audit trail on production: consecutive requests from one
  machine were recorded as 152.233.15.120, .121, .123 and 152.233.68.97.
  Tomcat's RemoteIpValve only trusts `X-Forwarded-For` from private-range
  proxies, and Railway's edge uses public addresses, so the header is
  (correctly) ignored. Trusting it needs Railway's published edge ranges in
  `server.tomcat.remoteip.internal-proxies`; guessing a range would let
  clients forge addresses, which is worse than recording the proxy.
- **An access token outlives sign-out by up to 15 minutes.** Access tokens
  are never looked up, so revoking the session (sign-out, detected token
  theft) stops refreshes at once but not the access token already issued.
  Closing that needs a denylist checked on every request; the short lifetime
  is the trade-off taken instead.
- **Only login is rate-limited, and only per email.** Registration has no
  limit, so one client can create accounts in bulk. There is no per-IP limit,
  because every client appears to come from a few Railway edge addresses
  (see the first gap); one becomes possible once client IPs are trusted.
- **Only booking accepts an `Idempotency-Key`.** Cancelling is idempotent
  by design (a second cancel returns the cancelled appointment unchanged), but a retried
  prescription or dispense gets a 409 rather than the original response.
- **Notifications are in-app only.** No email or SMS provider is configured,
  so a patient who does not open the app does not hear about a cancellation or
  a corrected prescription. Both would be further outbox consumers.
- **Pharmacy actions use the ADMIN role.** There is no dedicated pharmacist
  role yet, so whoever dispenses can also read the whole audit log.
- **The demo resets only nightly.** Between resets, one visitor's changes
  (closing the waiting visit, booking slots) are what the next visitor sees.
- **Nothing collects the metrics in production.** `/actuator/prometheus`
  works (ADMIN only), but no Prometheus server scrapes it, and there is no
  dashboard or alerting. The counters exist; nobody is watching them yet.
- **The load test runs only when started.** It is manual, or triggered by
  changes to the test itself, so a performance regression in application code
  would not be caught automatically. Its numbers come from a shared CI runner
  and vary between runs.
- **Frontend tests cover the API client and the booking page only.** The
  doctor workspace, the portal pages and sign-in have no tests, and there is
  no browser end-to-end test.

