// Booking load test. Run by .github/workflows/load-test.yml against a fresh
// API and database seeded with loadtest/seed.sql.
//
// Three scenarios:
//   contention          200 patients at once, 10 on each of 20 slots.
//                       Exactly 20 may win; everyone else must get a clean 409.
//   booking_throughput  a steady stream of bookings on free slots.
//   reads               the browsing traffic that runs alongside it.
//
// k6 only sees status codes. loadtest/verify.sql then checks the database.

import http from "k6/http";
import { check } from "k6";
import { Counter } from "k6/metrics";
import exec from "k6/execution";

const BASE = __ENV.BASE_URL || "http://localhost:8080";
const PASSWORD = "load-test-password";
const PATIENTS = 300;
const HOT_DOCTOR = "d0000000-0000-4000-8000-000000000001";
const COLD_DOCTORS = [2, 3, 4].map((n) => `d0000000-0000-4000-8000-${String(n).padStart(12, "0")}`);

const RATE = Number(__ENV.BOOKINGS_PER_SECOND || 40);
const DURATION = __ENV.DURATION || "60s";

const booked = new Counter("bookings_booked");
const lostRace = new Counter("bookings_lost_race");
const unexpected = new Counter("bookings_unexpected");

// 409 is a correct answer for a slot someone else won; it must not count as
// a failed request.
http.setResponseCallback(http.expectedStatuses(200, 201, 409));

export const options = {
  setupTimeout: "120s",
  scenarios: {
    contention: {
      executor: "per-vu-iterations",
      exec: "contend",
      vus: 200,
      iterations: 1,
      maxDuration: "30s",
    },
    booking_throughput: {
      executor: "constant-arrival-rate",
      exec: "bookFreeSlot",
      rate: RATE,
      timeUnit: "1s",
      duration: DURATION,
      preAllocatedVUs: 50,
      maxVUs: 200,
      startTime: "10s",
    },
    reads: {
      executor: "constant-arrival-rate",
      exec: "browse",
      rate: 60,
      timeUnit: "1s",
      duration: DURATION,
      preAllocatedVUs: 30,
      maxVUs: 150,
      startTime: "10s",
    },
  },
  thresholds: {
    // Correctness first: any status other than 201 or 409 on a booking fails the run.
    bookings_unexpected: ["count==0"],
    // The claim under test: 200 simultaneous attempts on 20 slots produce
    // exactly 20 bookings and 180 clean refusals.
    "bookings_booked{scenario:contention}": ["count==20"],
    "bookings_lost_race{scenario:contention}": ["count==180"],
    checks: ["rate==1"],
    http_req_failed: ["rate<0.01"],
    // Latency budgets for a shared 4-vCPU CI runner hosting the API, the
    // database and k6 together; production hardware is not being measured.
    "http_req_duration{scenario:booking_throughput}": ["p(95)<500"],
    "http_req_duration{scenario:reads}": ["p(95)<300"],
  },
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
};

export function setup() {
  // Sign in every patient in parallel batches; tokens live 15 minutes, far
  // longer than the run.
  const tokens = [];
  for (let start = 1; start <= PATIENTS; start += 50) {
    const batch = [];
    for (let n = start; n < start + 50 && n <= PATIENTS; n++) {
      batch.push(["POST", `${BASE}/api/v1/auth/login`,
        JSON.stringify({ email: `lt-patient-${n}@medicity.test`, password: PASSWORD }),
        { headers: { "Content-Type": "application/json" }, tags: { name: "setup-login" } }]);
    }
    for (const res of http.batch(batch)) {
      if (res.status !== 200) throw new Error(`login failed: ${res.status} ${res.body}`);
      tokens.push(res.json("accessToken"));
    }
  }

  const from = new Date().toISOString();
  const to = new Date(Date.now() + 40 * 86_400_000).toISOString();
  const slotsOf = (doctor) => http
    .get(`${BASE}/api/v1/doctors/${doctor}/slots?from=${from}&to=${to}`, { tags: { name: "setup-slots" } })
    .json()
    .map((s) => s.id);

  const hot = slotsOf(HOT_DOCTOR);
  const cold = COLD_DOCTORS.flatMap(slotsOf);
  if (hot.length !== 20) throw new Error(`expected 20 hot slots, got ${hot.length}`);
  return { tokens, hot, cold };
}

function book(token, slotId) {
  return http.post(`${BASE}/api/v1/appointments`, JSON.stringify({ slotId, reason: "Load test" }), {
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
      "Idempotency-Key": `${exec.scenario.name}-${exec.scenario.iterationInTest}`,
    },
    tags: { name: "POST /appointments" },
  });
}

function record(res) {
  if (res.status === 201) booked.add(1);
  else if (res.status === 409 && res.json("code") === "SLOT_ALREADY_BOOKED") lostRace.add(1);
  else unexpected.add(1);
}

export function contend(data) {
  // iterationInTest is 0..199 here. VU ids are shared across scenarios, so
  // they cannot be used to pick a distinct patient.
  const i = exec.scenario.iterationInTest;
  const res = book(data.tokens[i], data.hot[i % 20]);
  record(res);
  check(res, { "contention: 201 or SLOT_ALREADY_BOOKED": (r) => r.status === 201 || r.status === 409 });
}

export function bookFreeSlot(data) {
  const i = exec.scenario.iterationInTest;
  if (i >= data.cold.length) return;
  // Each iteration books a slot nobody else is trying for, so every request
  // should succeed: this measures the cost of a booking, not of a race.
  const res = book(data.tokens[i % PATIENTS], data.cold[i]);
  record(res);
  check(res, { "throughput: booked": (r) => r.status === 201 });
}

export function browse(data) {
  const i = exec.scenario.iterationInTest;
  const token = data.tokens[i % PATIENTS];
  const auth = { headers: { Authorization: `Bearer ${token}` } };
  let res;
  switch (i % 3) {
    case 0:
      res = http.get(`${BASE}/api/v1/doctors?specialization=Load%20Testing`, { tags: { name: "GET /doctors" } });
      break;
    case 1: {
      const from = new Date().toISOString();
      const to = new Date(Date.now() + 7 * 86_400_000).toISOString();
      res = http.get(`${BASE}/api/v1/doctors/${COLD_DOCTORS[i % 3]}/slots?from=${from}&to=${to}`,
        { tags: { name: "GET /doctors/{id}/slots" } });
      break;
    }
    default:
      res = http.get(`${BASE}/api/v1/patients/me/appointments?scope=upcoming`,
        { ...auth, tags: { name: "GET /patients/me/appointments" } });
  }
  check(res, { "reads: 200": (r) => r.status === 200 });
}

export function handleSummary(data) {
  const m = data.metrics;
  const v = (name, stat) => (m[name] && m[name].values[stat] !== undefined ? m[name].values[stat] : 0);
  const ms = (name, stat) => `${v(name, stat).toFixed(1)} ms`;
  const lines = [
    "## Booking load test",
    "",
    "| Measure | Value |",
    "|---|---|",
    `| Contention: 200 patients on 20 slots | ${v("bookings_booked{scenario:contention}", "count")} booked, ${v("bookings_lost_race{scenario:contention}", "count")} refused with 409 |`,
    `| Bookings succeeded (all scenarios) | ${v("bookings_booked", "count")} |`,
    `| Lost races (409 SLOT_ALREADY_BOOKED) | ${v("bookings_lost_race", "count")} |`,
    `| Unexpected booking responses | ${v("bookings_unexpected", "count")} |`,
    `| Booking p95 / p99 (throughput) | ${ms("http_req_duration{scenario:booking_throughput}", "p(95)")} / ${ms("http_req_duration{scenario:booking_throughput}", "p(99)")} |`,
    `| Read p95 / p99 | ${ms("http_req_duration{scenario:reads}", "p(95)")} / ${ms("http_req_duration{scenario:reads}", "p(99)")} |`,
    `| Requests / second (overall) | ${v("http_reqs", "rate").toFixed(1)} |`,
    `| Failed requests | ${(v("http_req_failed", "rate") * 100).toFixed(2)}% |`,
    "",
  ];
  return {
    stdout: lines.join("\n") + "\n",
    "/results/summary.md": lines.join("\n") + "\n",
    "/results/summary.json": JSON.stringify(data, null, 2),
  };
}
