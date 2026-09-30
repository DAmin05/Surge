#!/usr/bin/env python3
"""Headline load test: sell out a 10,000-seat event through the gateway, measure it,
and prove nothing was oversold. Optionally kill a Redis primary at peak.

    RATE_LIMIT_IP_PER_SEC=100000 make up      # production timings: T = 2 min, grace 30 s
    scripts/headline.py                        # warm-up, then the sell-out
    scripts/headline.py --chaos kill-redis-primary --chaos-at 60

Steps:
  1. Warm-up: 60 s of buyers on a throwaway event, so JIT and pools are warm (a cold
     JVM makes the first minute look ten times slower than it is).
  2. Seed the headline event (10 sections x 20 rows x 50 seats = 10,000).
  3. k6 buyers ramp to --peak buyers/s and hold; while they run, sample sold seats from
     Postgres every second, Prometheus series, and CPU per container.
  4. Wait for every order to settle, then verify: Reconciler 0 violations; sold seats
     = tickets issued = confirmed order items; no seat with two tickets; Redis sold set
     = Postgres sold set.
  5. Write docs/results/headline/<name>.json (summary + time series).

Standard library only; talks to the stack through `docker compose` and localhost ports.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from chaos_run import COMPOSE, Failed, chaos, internal, scalar, seed, sh, sql  # noqa: E402

PROM = os.environ.get("PROMETHEUS_URL", "http://localhost:9090")
OUT = "docs/results/headline"


def prom_range(query: str, start: float, end: float, step: int = 5) -> list[list[float]]:
    qs = urllib.parse.urlencode({"query": query, "start": start, "end": end, "step": step})
    with urllib.request.urlopen(f"{PROM}/api/v1/query_range?{qs}", timeout=30) as r:
        result = json.load(r)["data"]["result"]
    if not result:
        return []
    return [[float(t) - start, float(v)] for t, v in result[0]["values"] if v not in ("NaN", "+Inf")]


def k6(event: int, stages: str, name: str, out_dir: str) -> tuple[int, str]:
    os.makedirs(out_dir, exist_ok=True)
    # k6 runs as an unprivileged user: give it a scratch dir, then copy the summary back.
    scratch = tempfile.mkdtemp(prefix="surge-k6-")
    os.chmod(scratch, 0o777)
    cmd = [*COMPOSE, "--profile", "load", "run", "--rm", "--no-deps",
           "-v", f"{scratch}:/out",
           "-e", f"EVENT_ID={event}", "-e", f"STAGES={stages}", "-e", "WALK_AWAY=0", "-e", "LIVE_MAP=1",
           "k6", "run", "--quiet", f"--summary-export=/out/{name}.k6.json", "buyer.js"]
    r = subprocess.run(cmd, capture_output=True)
    if os.path.exists(f"{scratch}/{name}.k6.json"):
        shutil.copy(f"{scratch}/{name}.k6.json", f"{out_dir}/{name}.k6.json")
    shutil.rmtree(scratch, ignore_errors=True)
    return r.returncode, r.stdout.decode()[-4000:]


class Sampler(threading.Thread):
    """Seats sold (Postgres, 1 s) and CPU per container (docker stats, ~5 s)."""

    def __init__(self, event: int, t0: float):
        super().__init__(daemon=True)
        self.event, self.t0 = event, t0
        self.sold: list[list[float]] = []
        self.cpu: dict[str, list[float]] = {}
        self.stop = threading.Event()

    def run(self) -> None:
        last_cpu = 0.0
        while not self.stop.is_set():
            now = time.time()
            try:
                n = scalar(f"SELECT count(*) FROM orders.seats WHERE event_id = {self.event} AND status = 'SOLD'")
                self.sold.append([round(now - self.t0, 1), n])
            except Failed:
                pass
            if now - last_cpu >= 5:
                last_cpu = now
                out = sh("docker", "stats", "--no-stream", "--format", "{{.Name}} {{.CPUPerc}}", check=False)
                for line in out.splitlines():
                    name, pct = line.rsplit(" ", 1)
                    svc = name.removeprefix("surge-").rsplit("-", 1)[0]
                    if svc.startswith("k6"):
                        svc = "k6 (load generator)"
                    self.cpu.setdefault(svc, []).append(float(pct.rstrip("%") or 0))
            self.stop.wait(1)


def verify(event: int, sections: list[str]) -> dict:
    """Waits for the sale to settle, then checks every correctness property."""
    deadline = time.time() + 400  # T (2 min) + grace + slack
    while scalar(f"SELECT count(*) FROM orders.orders WHERE event_id = {event}"
                 f" AND state NOT IN ('CONFIRMED','CANCELLED','FAILED')"):
        if time.time() > deadline:
            raise Failed("orders did not settle")
        time.sleep(2)
    time.sleep(3)
    audit = json.loads(internal("http://reconciler:8001/audit", "POST"))
    facts = {
        "capacity": scalar(f"SELECT capacity FROM orders.events WHERE id = {event}"),
        "seats_sold": scalar(f"SELECT count(*) FROM orders.seats WHERE event_id = {event} AND status = 'SOLD'"),
        "tickets": scalar(f"SELECT count(*) FROM orders.tickets t JOIN orders.seats s ON s.id = t.seat_id"
                          f" WHERE s.event_id = {event}"),
        "confirmed_items": scalar(f"SELECT count(*) FROM orders.order_items i JOIN orders.orders o"
                                  f" ON o.id = i.order_id WHERE o.event_id = {event} AND o.state = 'CONFIRMED'"),
        "seats_with_two_tickets": scalar(f"SELECT count(*) FROM (SELECT seat_id FROM orders.tickets t JOIN"
                                         f" orders.seats s ON s.id = t.seat_id WHERE s.event_id = {event}"
                                         f" GROUP BY seat_id HAVING count(*) > 1) x"),
        "orders": dict(line.split("|") for line in sql(
            f"SELECT state, count(*) FROM orders.orders WHERE event_id = {event} GROUP BY state").splitlines()),
        "reconciler_violations": audit.get("total_violations"),
        "reconciler_errors": audit.get("errors"),
    }
    drift = 0
    for s in sections:
        snap = json.loads(internal(f"http://localhost:8082/sections/{event}/{s}/snapshot"))
        pg = {int(x) for x in sql(f"SELECT id FROM orders.seats WHERE event_id = {event} AND section = '{s}'"
                                  f" AND status = 'SOLD'").split()}
        drift += len(set(snap["sold"]) ^ pg)
    facts["redis_postgres_sold_mismatch"] = drift
    ok = (facts["seats_sold"] == facts["tickets"] == facts["confirmed_items"] <= facts["capacity"]
          and facts["seats_with_two_tickets"] == 0 and drift == 0
          and facts["reconciler_violations"] == 0 and facts["reconciler_errors"] == 0)
    facts["ok"] = ok
    return facts


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--name", default="sellout")
    ap.add_argument("--peak", type=int, default=50, help="buyers arriving per second at peak")
    ap.add_argument("--ramp", default="30s")
    ap.add_argument("--hold", default="4m")
    ap.add_argument("--chaos", help="chaos action to inject during the sale")
    ap.add_argument("--chaos-at", type=float, default=60, help="seconds after the sale starts")
    ap.add_argument("--chaos-duration", type=float, default=30)
    ap.add_argument("--no-warmup", action="store_true")
    a = ap.parse_args()

    if not a.no_warmup:
        print("warm-up: 60 s at 20 buyers/s on a throwaway event", flush=True)
        warm = seed(10, 10, 20, "warm-up")
        k6(warm, "10s:20,50s:20", "warmup", "/tmp/surge-warmup")

    sections, rows, per_row = 10, 20, 50
    event = seed(sections, rows, per_row, f"Headline: {a.name}")
    names = [chr(65 + i) for i in range(sections)]
    print(f"event {event}: {sections * rows * per_row:,} seats; ramp {a.ramp} to {a.peak}/s, hold {a.hold}",
          flush=True)

    t0 = time.time()
    sampler = Sampler(event, t0)
    sampler.start()
    chaos_window = None
    if a.chaos:
        def inject() -> None:
            nonlocal chaos_window
            time.sleep(a.chaos_at)
            ev = chaos(f"/actions/{a.chaos}", "POST", {"durationS": a.chaos_duration})
            chaos_window = [round(ev["started_at"] - t0, 1), round(ev["started_at"] - t0 + a.chaos_duration, 1),
                            ev.get("detail")]
            print(f"chaos: {a.chaos} at {chaos_window[0]} s ({ev.get('detail')})", flush=True)
        threading.Thread(target=inject, daemon=True).start()

    code, k6_out = k6(event, f"{a.ramp}:{a.peak},{a.hold}:{a.peak}", a.name, OUT)
    t_end = time.time()
    chaos("/reset", "POST")
    print(f"k6 exited {code} after {t_end - t0:.0f} s; verifying", flush=True)
    facts = verify(event, names)
    sampler.stop.set()
    sampler.join()

    sold = sampler.sold
    sold_out_at = next((t for t, n in sold if n >= facts["capacity"]), None)
    half_at = next((t for t, n in sold if n >= facts["capacity"] / 2), None)
    series = {
        "seats_sold": sold,
        "requests_per_s": prom_range("sum(rate(gateway_requests_total[15s]))", t0, t_end),
        "checkouts_per_s": prom_range('sum(rate(gateway_requests_total{route="checkout",status="201"}[15s]))',
                                      t0, t_end),
        "checkout_p50_ms": prom_range('1000 * histogram_quantile(0.5, sum by (le) '
                                      '(rate(gateway_upstream_seconds_bucket{route="checkout"}[15s])))', t0, t_end),
        "checkout_p99_ms": prom_range('1000 * histogram_quantile(0.99, sum by (le) '
                                      '(rate(gateway_upstream_seconds_bucket{route="checkout"}[15s])))', t0, t_end),
        "hold_p99_ms": prom_range('1000 * histogram_quantile(0.99, sum by (le) '
                                  '(rate(gateway_upstream_seconds_bucket{route="hold"}[15s])))', t0, t_end),
    }
    k6_summary = {}
    try:
        with open(f"{OUT}/{a.name}.k6.json") as f:
            m = json.load(f)["metrics"]
        pick = lambda k: {x: round(v, 1) for x, v in m.get(k, {}).items() if x in ("avg", "p(50)", "p(95)", "p(99)", "max", "count", "rate")}  # noqa: E731
        k6_summary = {k: pick(k) for k in ("checkout_ms", "hold_ms", "admit_wait_ms", "order_settle_ms",
                                           "http_reqs", "iterations", "unexpected_status", "idempotency_broken")}
        k6_summary["outcomes"] = {k.split("result:")[1].rstrip("}"): int(v.get("count", 0))
                                  for k, v in m.items() if k.startswith("buyer_outcomes{result:")}
    except (OSError, KeyError, ValueError):
        pass
    result = {
        "name": a.name, "event": event, "peak_buyers_per_s": a.peak, "stages": f"{a.ramp}:{a.peak},{a.hold}:{a.peak}",
        "started_at": t0, "duration_s": round(t_end - t0), "k6_exit": code,
        "sold_out_at_s": sold_out_at, "half_sold_at_s": half_at, "chaos": a.chaos, "chaos_window": chaos_window,
        "verify": facts, "k6": k6_summary,
        "cpu_avg_percent": {k: round(sum(v) / len(v), 1) for k, v in sampler.cpu.items() if v},
        "series": series,
        "machine": {"cpus": os.cpu_count()},
    }
    os.makedirs(OUT, exist_ok=True)
    with open(f"{OUT}/{a.name}.json", "w") as f:
        json.dump(result, f, indent=1)
    print(json.dumps({k: result[k] for k in ("sold_out_at_s", "half_sold_at_s", "chaos_window", "k6_exit")}))
    print(json.dumps(facts))
    if code != 0:
        print(k6_out)
    ok = facts["ok"] and code == 0
    print("HEADLINE OK" if ok else "HEADLINE FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
