#!/usr/bin/env python3
"""Chaos scenarios: k6 buyers against the running stack while one fault is injected
mid-sale, then proof that nothing went wrong.

Each run seeds a fresh event, starts the k6 buyers, triggers the scenario's chaos
action while they buy, waits for the saga to settle and then requires:

  - every order terminal, no pending charge, every late success refunded;
  - Redis agrees with Postgres: no holds left, and each section's sold set is exactly
    the seats Postgres sold (the map may lose events during chaos, never stay wrong);
  - the Reconciler reports 0 violations and 0 check errors;
  - the scenario's own expectation (the fault bit: declines, refunds, lapsed holds...).

Needs the stack running with a short payment timeout and lifted per-IP limits, e.g.

    PAYMENT_TIMEOUT=PT10S PIN_GRACE=PT10S HOLD_LEASE=PT20S RATE_LIMIT_IP_PER_SEC=100000 make up
    scripts/chaos_run.py                      # every scenario once
    scripts/chaos_run.py --runs 20            # 20 runs, cycling through the scenarios
    scripts/chaos_run.py --shard 1/4 --runs 20

Standard library only; talks to the stack through `docker compose` and the chaos port.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
from dataclasses import dataclass, field
from typing import Callable

CHAOS = os.environ.get("CHAOS_URL", "http://localhost:8002")
DB = os.environ.get("POSTGRES_DB", "surge")
ADMIN = os.environ.get("POSTGRES_ADMIN_USER", "surge_admin")
COMPOSE = ["docker", "compose"]


def seconds(iso: str) -> float:
    m = re.fullmatch(r"PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?", iso)
    if not m or iso == "PT":
        raise SystemExit(f"not a PT duration: {iso!r}")
    h, mi, s = m.groups()
    return int(h or 0) * 3600 + int(mi or 0) * 60 + float(s or 0)


T = seconds(os.environ.get("PAYMENT_TIMEOUT", "PT10S"))
GRACE = seconds(os.environ.get("PIN_GRACE", "PT10S"))
LATE_SUCCESS = T + GRACE + 5  # what the payment-timeouts action stalls charges for


class Failed(Exception):
    pass


# ------------------------------------------------------------------ stack access


def sh(*args: str, stdin: bytes | None = None, check: bool = True) -> str:
    r = subprocess.run(args, input=stdin, capture_output=True, check=False)
    if check and r.returncode != 0:
        raise Failed(f"{' '.join(args[:6])}...: {r.stderr.decode()[-400:]}")
    return r.stdout.decode().strip()


def sql(query: str) -> str:
    return sh(*COMPOSE, "exec", "-T", "postgres", "psql", "-qtA", "-U", ADMIN, "-d", DB, "-c", query)


def scalar(query: str) -> int:
    return int(sql(query) or 0)


def internal(url: str, method: str = "GET", body: dict | None = None) -> str:
    """HTTP inside the Compose network (the inventory container has curl)."""
    extra = ["-H", "Content-Type: application/json", "-d", json.dumps(body)] if body is not None else []
    return sh(*COMPOSE, "exec", "-T", "inventory", "curl", "-sf", "-X", method, *extra, url)


def chaos(path: str, method: str = "GET", body: dict | None = None) -> object:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(f"{CHAOS}{path}", data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read() or b"null")


def metric(name: str) -> float:
    text = internal("http://localhost:8082/actuator/prometheus")
    total = 0.0
    for line in text.splitlines():
        if line.startswith(name + "{") or line.startswith(name + " "):
            total += float(line.rsplit(" ", 1)[1])
    return total


def seed(sections: int, rows: int, per_row: int, name: str) -> int:
    with open("infra/postgres/seed.sql", "rb") as f:
        out = sh(*COMPOSE, "exec", "-T", "postgres", "psql", "-qtA", "-U", "orders_owner", "-d", DB,
                 "-v", f"sections={sections}", "-v", f"rows={rows}", "-v", f"seats_per_row={per_row}",
                 "-v", f"name={name}", stdin=f.read())
    return int(out.splitlines()[-1])


# ------------------------------------------------------------------ scenarios


@dataclass
class Run:
    event: int
    before: dict[str, float] = field(default_factory=dict)
    k6: str = ""
    chaos_detail: str | None = None

    def count(self, where: str) -> int:
        return scalar(f"SELECT count(*) FROM orders.orders o LEFT JOIN payments.payments p"
                      f" USING (payment_key) WHERE o.event_id = {self.event} AND {where}")


@dataclass
class Scenario:
    id: str
    action: str
    duration_s: float
    what: str
    expect: Callable[[Run], str] = lambda r: ""
    at_s: float = 10  # seconds into the sale; negative: before the first buyer arrives
    rate: int = 10
    load_s: int = 40
    skew: float = 0.5
    seats: tuple[int, int, int] = (3, 4, 10)  # sections, rows, seats per row
    faults: dict | None = None  # Payment faults for the whole run (on top of the action)


def expect_cancelled(r: Run) -> str:
    n = r.count("o.state = 'CANCELLED'")
    if n == 0:
        raise Failed("the fault didn't bite: no cancelled orders")
    return f"{n} cancelled"


def expect_refunds(r: Run) -> str:
    n = r.count("p.status = 'REFUNDED'")
    if n == 0:
        raise Failed("no late success was refunded")
    return f"{n} late successes refunded"


def expect_resold_refund(r: Run) -> str:
    """The legal race: A's seats released and bought by B, then A's payment succeeds
    late. A must be refunded and B keep the seat."""
    n = scalar(f"""
        SELECT count(DISTINCT ai.seat_id)
          FROM orders.orders a
          JOIN payments.payments p ON p.payment_key = a.payment_key AND p.status = 'REFUNDED'
          JOIN orders.order_items ai ON ai.order_id = a.id
          JOIN orders.order_items bi ON bi.seat_id = ai.seat_id AND bi.order_id <> a.id
          JOIN orders.orders b ON b.id = bi.order_id AND b.state = 'CONFIRMED'
         WHERE a.event_id = {r.event}""")
    if n == 0:
        raise Failed("no refunded seat was resold to someone else")
    return f"{n} seats resold, their late payers refunded"


def expect_lapsed(r: Run) -> str:
    moved = metric("holds_expired_while_reserved_total") - r.before["lapsed"]
    if moved <= 0:
        raise Failed("holds_expired_while_reserved didn't move")
    return f"holds_expired_while_reserved +{moved:.0f}"


def expect_detail(r: Run) -> str:
    if not r.chaos_detail:
        raise Failed("chaos action reported no target")
    return f"killed {r.chaos_detail}"


def expect_rebuilt(r: Run) -> str:
    moved = metric("section_rebuilds_total") - r.before["rebuilds"]
    return f"killed {r.chaos_detail}, {moved:.0f} section rebuilds"


SCENARIOS = [
    Scenario("kill-redis-primary", "kill-redis-primary", 20,
             "a Redis primary dies mid-sale; its replica takes over", expect_rebuilt),
    Scenario("kill-inventory", "kill-inventory", 15, "Inventory is down for 15 s"),
    Scenario("payment-failures", "payment-failures", 20, "Payment declines 40 %", expect_cancelled),
    Scenario("payment-timeouts", "payment-timeouts", 20,
             "half the charges stall past T, then succeed", expect_refunds),
    Scenario("late-success-resale", "payment-timeouts", 20,
             "stalled buyers lose their seats to others, then pay late", expect_resold_refund,
             at_s=-1, load_s=50, skew=0.9, seats=(1, 2, 10)),
    Scenario("duplicate-callbacks", "duplicate-callbacks", 20,
             "every webhook delivered 2-3 times, plus stale failures"),
    Scenario("partition-order-postgres", "partition-order-postgres", 15,
             "Order cut off from Postgres"),
    Scenario("slow-order-postgres", "slow-order-postgres", 20, "300 ms Order↔Postgres latency"),
    Scenario("restart-redpanda", "restart-redpanda", 0, "the broker restarts mid-sale"),
    # Slow charges keep confirmations arriving while the relay is paused; their
    # OrderConfirmed waits in the outbox past the pinned lease.
    Scenario("pause-outbox-relay", "pause-outbox-relay", T + GRACE + 15,
             "the outbox relay stalls past the holds' grace", expect_lapsed,
             at_s=5, seats=(3, 10, 10), faults={"latency_ms_min": 2000, "latency_ms_max": 4000}),
]
BY_ID = {s.id: s for s in SCENARIOS}


# ------------------------------------------------------------------ one run


def settle(run: Run, sections: list[str], deadline_s: float) -> None:
    deadline = time.time() + deadline_s
    last = ""
    while True:
        open_ = run.count("o.state NOT IN ('CONFIRMED', 'CANCELLED', 'FAILED')")
        pending = run.count("p.status = 'PENDING'")
        unrefunded = run.count("p.status = 'CAPTURED' AND o.state = 'CANCELLED'")
        drift = []
        for s in sections:
            snap = json.loads(internal(f"http://localhost:8082/sections/{run.event}/{s}/snapshot"))
            pg = {int(x) for x in sql(f"SELECT id FROM orders.seats WHERE event_id = {run.event}"
                                      f" AND section = '{s}' AND status = 'SOLD'").split()}
            if snap["held"]:
                drift.append(f"{s}: {len(snap['held'])} held")
            if set(snap["sold"]) != pg:
                drift.append(f"{s}: redis sold {len(snap['sold'])} vs postgres {len(pg)}")
        last = f"open={open_} pending={pending} unrefunded={unrefunded} {' '.join(drift)}".strip()
        if open_ == pending == unrefunded == 0 and not drift:
            return
        if time.time() > deadline:
            raise Failed(f"not settled: {last}")
        time.sleep(2)


def run_once(sc: Scenario, n: int) -> str:
    sections, rows, per_row = sc.seats
    run = Run(seed(sections, rows, per_row, f"chaos {sc.id} #{n}"))
    names = [chr(65 + i) for i in range(sections)]
    chaos("/reset", "POST")
    internal("http://payment:8000/faults", "DELETE")
    if sc.faults:
        internal("http://payment:8000/faults", "PUT", sc.faults)
    run.before = {"lapsed": metric("holds_expired_while_reserved_total"),
                  "rebuilds": metric("section_rebuilds_total")}

    def inject() -> None:
        ev = chaos(f"/actions/{sc.action}", "POST",
                   {"durationS": sc.duration_s} if sc.duration_s else {})
        run.chaos_detail = ev.get("detail") if isinstance(ev, dict) else None

    if sc.at_s < 0:
        inject()
    k6 = subprocess.Popen(
        [*COMPOSE, "--profile", "load", "run", "--rm", "--no-deps",
         "-e", f"EVENT_ID={run.event}", "-e", f"RATE={sc.rate}", "-e", f"DURATION={sc.load_s}s",
         "-e", f"SKEW={sc.skew}", "-e", f"ORDER_WAIT_S={int(LATE_SUCCESS + T + 30)}",
         "k6", "run", "--quiet", "buyer.js"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    try:
        if sc.at_s >= 0:
            time.sleep(sc.at_s + 3)  # the k6 container takes a moment to start
            inject()
        out, _ = k6.communicate(timeout=sc.load_s + LATE_SUCCESS + T + 180)
    except BaseException:
        k6.kill()
        raise
    finally:
        chaos("/reset", "POST")
        internal("http://payment:8000/faults", "DELETE")
    run.k6 = out.decode()
    if k6.returncode != 0:
        lines = run.k6.splitlines()
        tail = "\n".join([x for x in lines if "UNEXPECTED" in x][:10] + lines[-25:])
        raise Failed(f"k6 exited {k6.returncode} (thresholds: idempotency or unexpected status)\n{tail}")

    # Charges stalled by the fault succeed LATE_SUCCESS after they started.
    settle(run, names, LATE_SUCCESS + 3 * T + 120)
    audit = json.loads(internal("http://reconciler:8001/audit", "POST"))
    if audit.get("total_violations") or audit.get("errors"):
        raise Failed(f"reconciler: {json.dumps(audit)[:600]}")
    extra = sc.expect(run)

    by_state = sql(f"SELECT string_agg(state || '=' || n, ' ' ORDER BY state) FROM"
                   f" (SELECT state, count(*) n FROM orders.orders WHERE event_id = {run.event}"
                   f" GROUP BY state) s")
    return f"event {run.event}: {by_state}; {extra}".rstrip("; ")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--runs", type=int, default=len(SCENARIOS), help="total runs, cycling through the scenarios")
    ap.add_argument("--scenarios", help="comma-separated subset (default: all)")
    ap.add_argument("--shard", default="1/1", help="i/n: take every n-th run starting at i")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--out", help="append one JSON line per run to this file")
    a = ap.parse_args()

    if a.list:
        for s in SCENARIOS:
            print(f"{s.id:26} {s.what}")
        return 0
    chosen = [BY_ID[x] for x in a.scenarios.split(",")] if a.scenarios else SCENARIOS
    i, n = (int(x) for x in a.shard.split("/"))
    plan = [(k + 1, chosen[k % len(chosen)]) for k in range(a.runs) if k % n == i - 1]

    failures = 0
    for k, sc in plan:
        started = time.time()
        try:
            result, ok = run_once(sc, k), True
        except Failed as e:
            result, ok = str(e), False
            failures += 1
        took = time.time() - started
        print(f"{'ok  ' if ok else 'FAIL'} run {k:2} {sc.id:26} {took:5.0f}s  {result}", flush=True)
        if a.out:
            with open(a.out, "a") as f:
                f.write(json.dumps({"run": k, "scenario": sc.id, "ok": ok, "seconds": round(took),
                                    "result": result}) + "\n")
    print(f"{'CHAOS OK' if not failures else 'CHAOS FAILED'}: {len(plan) - failures}/{len(plan)} runs"
          f" with 0 invariant violations")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
