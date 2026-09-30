// Booking load test. Run by .github/workflows/load-test.yml against a fresh
// API and database seeded with loadtest/seed.sql.
//
// Five scenarios:
//   contention          200 patients at once, 10 on each of 20 slots.
//                       Exactly 20 may win; everyone else must get a clean 409.
//   booking_throughput  a steady stream of bookings on free slots.
//   reads               the browsing traffic that runs alongside it: the
//                       directory page, a search, a doctor's slots, my visits.
//   queue_join          all 300 patients take a walk-in token at one doctor
//                       at the same moment: tokens 1..300, no gaps, no repeats.
//   queue_call          then 8 front-desk tabs press "Call next" together
//                       until nobody is waiting: each token called once.
// The queue gives tokens between 7 am and 9 pm India time; outside those
// hours the two queue scenarios are skipped and the summary says so.
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
const tokensGiven = new Counter("queue_tokens_given");
const tokensCalled = new Counter("queue_tokens_called");
const queueUnexpected = new Counter("queue_unexpected");

const QUEUE_DOCTOR = "d0000000-0000-4000-8000-000000000005";
const DESK_TABS = 8;

/** The walk-in queue's hours, 7 am to 9 pm in India (UTC+5:30). */
function queueOpen() {
  const ist = new Date(Date.now() + 330 * 60_000);
  const minutes = ist.getUTCHours() * 60 + ist.getUTCMinutes();
  return minutes >= 7 * 60 && minutes < 21 * 60 - 5;
}

// 409 is a correct answer for a slot someone else won, and 422 QUEUE_EMPTY
// for a desk tab that found nobody left; neither is a failed request.
http.setResponseCallback(http.expectedStatuses(200, 201, 409, 422));

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
    queue_join: {
      executor: "per-vu-iterations",
      exec: "joinQueue",
      vus: PATIENTS,
      iterations: 1,
      maxDuration: "30s",
      startTime: "80s",
    },
    queue_call: {
      executor: "per-vu-iterations",
      exec: "callNext",
      vus: DESK_TABS,
      iterations: 1,
      maxDuration: "60s",
      startTime: "115s",
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
    queue_unexpected: ["count==0"],
    "http_req_duration{scenario:queue_join}": ["p(95)<2000"],
    "http_req_duration{scenario:queue_call}": ["p(95)<2000"],
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

  const desk = http.post(`${BASE}/api/v1/auth/login`,
    JSON.stringify({ email: "lt-doctor-5@medicity.test", password: PASSWORD }),
    { headers: { "Content-Type": "application/json" }, tags: { name: "setup-login" } });
  if (desk.status !== 200) throw new Error(`doctor login failed: ${desk.status}`);
  return { tokens, hot, cold, deskToken: desk.json("accessToken"), queueOpen: queueOpen() };
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

export function joinQueue(data) {
  if (!data.queueOpen) return;
  const i = exec.scenario.iterationInTest;
  const res = http.post(`${BASE}/api/v1/doctors/${QUEUE_DOCTOR}/queue`, JSON.stringify({ reason: "Load test" }), {
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${data.tokens[i]}` },
    tags: { name: "POST /doctors/{id}/queue" },
  });
  if (res.status === 201 || res.status === 200) tokensGiven.add(1);
  else queueUnexpected.add(1);
  check(res, { "queue: token given": (r) => r.status === 201 || r.status === 200 });
}

export function callNext(data) {
  if (!data.queueOpen) return;
  // Each desk tab keeps calling until the queue is empty. SKIP LOCKED must
  // hand every tab a different patient.
  for (let n = 0; n < 1000; n++) {
    const res = http.post(`${BASE}/api/v1/doctors/me/queue/next`, null, {
      headers: { Authorization: `Bearer ${data.deskToken}` },
      tags: { name: "POST /doctors/me/queue/next" },
    });
    if (res.status === 200) {
      tokensCalled.add(1);
    } else if (res.status === 422 && res.json("code") === "QUEUE_EMPTY") {
      return;
    } else {
      queueUnexpected.add(1);
      return;
    }
  }
}

export function browse(data) {
  const i = exec.scenario.iterationInTest;
  const token = data.tokens[i % PATIENTS];
  const auth = { headers: { Authorization: `Bearer ${token}` } };
  let res;
  switch (i % 5) {
    case 0:
      res = http.get(`${BASE}/api/v1/doctors?specialization=Load%20Testing`, { tags: { name: "GET /doctors" } });
      break;
    case 3:
      // The whole directory page: next free slots, ratings, insurers and
      // prices for 20 doctors, each in one query for the page.
      res = http.get(`${BASE}/api/v1/doctors?page=0&size=20`, { tags: { name: "GET /doctors (page)" } });
      break;
    case 4:
      res = http.get(`${BASE}/api/v1/doctors?q=load`, { tags: { name: "GET /doctors?q" } });
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
    `| Walk-in: 300 patients join one queue at once | ${v("queue_tokens_given", "count")} tokens given, p95 ${ms("http_req_duration{scenario:queue_join}", "p(95)")} |`,
    `| Walk-in: 8 desk tabs call next together | ${v("queue_tokens_called", "count")} tokens called, p95 ${ms("http_req_duration{scenario:queue_call}", "p(95)")} |`,
    `| Unexpected queue responses | ${v("queue_unexpected", "count")} |`,
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
