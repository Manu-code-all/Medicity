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

**Lesson.** A best-effort write that fails quietly needs a test that checks
the write happened, not only that the response was right.

---

## Known gaps (tracked, not hidden)

- **Audit IP addresses are Railway's edge proxies, not clients.** Found when
  checking the audit trail on production: consecutive requests from one
  machine were recorded as 152.233.15.120, .121, .123 and 152.233.68.97.
  Tomcat's RemoteIpValve only trusts `X-Forwarded-For` from private-range
  proxies, and Railway's edge uses public addresses, so the header is
  (correctly) ignored. Trusting it needs Railway's published edge ranges in
  `server.tomcat.remoteip.internal-proxies`; guessing a range would let
  clients forge addresses, which is worse than recording the proxy.
- **Expired refresh tokens and idempotency keys are never deleted.** Every
  refresh adds a row (roughly one per active user every 15 minutes), and
  spent, revoked and expired rows stay. Nothing reads them once expired, so
  this is growth, not a correctness issue. Planned: a nightly cleanup job
  alongside the other scheduled work.
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
- **A prescription can be corrected after it was dispensed** and no one is
  told. The pharmacy refuses the superseded original, but the patient may
  already hold its medicines. Planned: a notification through an outbox.
- **Pharmacy actions use the ADMIN role.** There is no dedicated pharmacist
  role yet, so whoever dispenses can also read the whole audit log.
- **The public demo is consumed by use.** Closing Dr. Rao's waiting visit or
  booking the open slots changes the data for the next visitor. Planned: a
  nightly reset of the demo data.

