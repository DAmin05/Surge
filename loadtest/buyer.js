// A flash-sale buyer, through the gateway like a browser:
//   session → waiting room → hold 1-4 seats in one section → check out → wait for the
//   order to settle. Some buyers change their mind and release, a few walk away and
//   let the hold expire.
//
// Each iteration is a new anonymous buyer (fresh session cookie). Buyers arrive at
// RATE per second for DURATION. SKEW of them want the first section (contention is per
// section, so this is the hot shard). Checkouts are retried with the same
// Idempotency-Key on 5xx, network errors and REQUEST_IN_PROGRESS; a retry that ever
// yields a second order id fails the run (`idempotency_broken`).
//
//   k6 run -e EVENT_ID=1 -e RATE=20 -e DURATION=60s buyer.js

import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Trend } from "k6/metrics";

const BASE = __ENV.BASE_URL || "http://gateway:8080";
const EVENT_ID = Number(__ENV.EVENT_ID);
const RATE = Number(__ENV.RATE || 20);
const DURATION = __ENV.DURATION || "60s";
const SKEW = Number(__ENV.SKEW || 0.5);
const RELEASE = Number(__ENV.RELEASE || 0.1);
const WALK_AWAY = Number(__ENV.WALK_AWAY || 0.03);
const ORDER_WAIT_S = Number(__ENV.ORDER_WAIT_S || 90);
const TERMINAL = ["CONFIRMED", "CANCELLED", "FAILED"];

export const options = {
  scenarios: {
    buyers: {
      executor: "constant-arrival-rate",
      rate: RATE,
      timeUnit: "1s",
      duration: DURATION,
      preAllocatedVUs: Math.max(10, RATE * 5),
      maxVUs: Math.max(50, RATE * 40),
      gracefulStop: `${ORDER_WAIT_S + 30}s`,
    },
  },
  thresholds: {
    // Correctness, not availability: chaos is allowed to fail requests.
    idempotency_broken: ["count==0"],
    unexpected_status: ["count==0"],
    // Always-true thresholds, only so the summary breaks buyers down by outcome.
    ...Object.fromEntries(
      [
        "order_CONFIRMED",
        "order_CANCELLED",
        "order_UNSETTLED",
        "released",
        "walked_away",
        "no_seats",
        "not_admitted",
        "checkout_SEAT_TAKEN",
        "checkout_USER_LIMIT",
        "checkout_HOLD_EXPIRED",
        "checkout_GAVE_UP",
      ].map((r) => [`buyer_outcomes{result:${r}}`, ["count>=0"]]),
    ),
  },
  summaryTrendStats: ["avg", "p(50)", "p(95)", "p(99)", "max"],
};

const outcomes = new Counter("buyer_outcomes");
const idempotencyBroken = new Counter("idempotency_broken");
const unexpected = new Counter("unexpected_status");
const admitWait = new Trend("admit_wait_ms", true);
const holdLatency = new Trend("hold_ms", true);
const checkoutLatency = new Trend("checkout_ms", true);
const settleTime = new Trend("order_settle_ms", true);

// Statuses a buyer can legitimately get under load and chaos. Anything else (400, 401,
// 404 on our own order...) is a bug and fails the run.
const OK_HOLD = [201, 409, 429, 502, 503, 504, 0];
const OK_CHECKOUT = [201, 200, 409, 410, 429, 502, 503, 504, 0];

export function setup() {
  const res = http.get(`${BASE}/api/events/${EVENT_ID}`);
  if (res.status !== 200) throw new Error(`catalog ${res.status}: ${res.body}`);
  const cat = res.json();
  return { sections: cat.sections.map((s) => ({ section: s.section, seats: s.seats.map((x) => x.id) })) };
}

const pick = (arr) => arr[Math.floor(Math.random() * arr.length)];
function sample(arr, n) {
  const copy = arr.slice();
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [copy[i], copy[j]] = [copy[j], copy[i]];
  }
  return copy.slice(0, n);
}
const json = (token, extra = {}) => ({
  headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}`, ...extra },
});
function body(res) {
  try {
    return res.json();
  } catch {
    return {};
  }
}

function bad(step, res) {
  unexpected.add(1, { step, status: String(res.status) });
  console.warn(`UNEXPECTED ${step} ${res.status}: ${String(res.body).slice(0, 200)}`);
}

function admitted() {
  const started = Date.now();
  let res = http.post(`${BASE}/api/queue/${EVENT_ID}/join`, null, { tags: { name: "join" } });
  for (let i = 0; i < 240; i++) {
    const q = body(res);
    if (q.status === "ADMITTED") {
      admitWait.add(Date.now() - started);
      return q.token;
    }
    sleep(0.5);
    res = http.get(`${BASE}/api/queue/${EVENT_ID}/position`, { tags: { name: "position" } });
    if (body(res).status === "NOT_IN_QUEUE") {
      res = http.post(`${BASE}/api/queue/${EVENT_ID}/join`, null, { tags: { name: "join" } });
    }
  }
  return null;
}

function hold(token, sections) {
  for (let attempt = 0; attempt < 4; attempt++) {
    const s = Math.random() < SKEW ? sections[0] : pick(sections);
    const n = pick([1, 2, 2, 2, 3, 4]);
    const res = http.post(
      `${BASE}/api/holds`,
      JSON.stringify({ eventId: EVENT_ID, section: s.section, seatIds: sample(s.seats, n) }),
      { ...json(token), tags: { name: "hold" } },
    );
    holdLatency.add(res.timings.duration);
    if (!OK_HOLD.includes(res.status)) bad("hold", res);
    if (res.status === 201) return body(res).holdId;
    if (res.status === 409 && body(res).error === "USER_LIMIT") return null;
    sleep(res.status === 409 ? 0.2 : 1);
  }
  return null;
}

function checkout(token, holdId) {
  const key = crypto.randomUUID();
  let orderId = null;
  for (let attempt = 0; attempt < 6; attempt++) {
    const res = http.post(`${BASE}/api/checkout`, JSON.stringify({ holdId }), {
      ...json(token, { "Idempotency-Key": key }),
      tags: { name: "checkout" },
    });
    checkoutLatency.add(res.timings.duration);
    if (!OK_CHECKOUT.includes(res.status)) bad("checkout", res);
    const b = body(res);
    if (res.status === 201 || res.status === 200) {
      if (orderId !== null && b.orderId !== orderId) idempotencyBroken.add(1);
      orderId = b.orderId;
      // Once in a while, prove a retry of a finished checkout replays the same order.
      if (Math.random() < 0.1) continue;
      return orderId;
    }
    const retryable = res.status === 0 || res.status >= 500 || b.error === "REQUEST_IN_PROGRESS" || res.status === 429;
    if (!retryable) return orderId === null ? { error: b.error || String(res.status) } : orderId;
    sleep(0.5 * 2 ** attempt);
  }
  return orderId === null ? { error: "GAVE_UP" } : orderId;
}

function settle(orderId) {
  const started = Date.now();
  while (Date.now() - started < ORDER_WAIT_S * 1000) {
    const res = http.get(`${BASE}/api/orders/${orderId}`, { tags: { name: "order" } });
    if (res.status === 200 && TERMINAL.includes(body(res).state)) {
      settleTime.add(Date.now() - started);
      return body(res).state;
    }
    if (res.status === 404) bad("order", res);
    sleep(1);
  }
  return "UNSETTLED";
}

export default function (data) {
  http.cookieJar().clear(BASE);
  const s = http.post(`${BASE}/api/session`, null, { tags: { name: "session" } });
  if (!check(s, { session: (r) => r.status === 200 || r.status === 201 })) return outcomes.add(1, { result: "no_session" });

  const token = admitted();
  if (!token) return outcomes.add(1, { result: "not_admitted" });

  const holdId = hold(token, data.sections);
  if (!holdId) return outcomes.add(1, { result: "no_seats" });

  const r = Math.random();
  if (r < RELEASE) {
    http.del(`${BASE}/api/holds/${encodeURIComponent(holdId)}`, null, { ...json(token), tags: { name: "release" } });
    return outcomes.add(1, { result: "released" });
  }
  if (r < RELEASE + WALK_AWAY) return outcomes.add(1, { result: "walked_away" });

  const order = checkout(token, holdId);
  if (typeof order !== "number") return outcomes.add(1, { result: `checkout_${order.error}` });
  outcomes.add(1, { result: `order_${settle(order)}` });
}
