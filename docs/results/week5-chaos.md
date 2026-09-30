# Week 5: chaos under load, and what it found

Exit criterion: **20 chaos runs, 0 invariant violations.** Each run seeds a fresh
event, sends k6 buyers through the gateway (10 buyers/s for 40-50 s), injects one fault
mid-sale through the chaos controller, waits for the saga to settle and then requires
every order terminal, no pending charge, every late success refunded, no holds left,
Redis' sold set equal to Postgres' for every section, 0 Reconciler violations and the
fault's own effect ([ADR 0009](../adr/0009-chaos-judges-correctness-after-settling.md)).
Saga timings for runs: T = 10 s, grace = 10 s, hold lease 20 s.

CI (`Exit: 20 chaos runs, 0 violations`) runs the same 20 on four runners, five each.

## Final local pass: 20/20

One 4-vCPU machine, whole stack, `scripts/chaos_run.py --runs 20`:

| Run | Scenario | Took | Outcome |
|---:|---|---:|---|
| 1 | `kill-redis-primary` | 46 s | event 67: CONFIRMED=63; killed redis-6, 3 section rebuilds |
| 2 | `kill-inventory` | 47 s | event 68: CONFIRMED=49 |
| 3 | `payment-failures` | 46 s | event 69: CANCELLED=4 CONFIRMED=67; 4 cancelled |
| 4 | `payment-timeouts` | 59 s | event 70: CANCELLED=4 CONFIRMED=63; 4 late successes refunded |
| 5 | `late-success-resale` | 55 s | event 71: CANCELLED=9 CONFIRMED=12; 10 seats resold, their late payers refunded |
| 6 | `duplicate-callbacks` | 58 s | event 72: CONFIRMED=63 |
| 7 | `partition-order-postgres` | 54 s | event 73: CONFIRMED=64 |
| 8 | `slow-order-postgres` | 46 s | event 74: CONFIRMED=61 |
| 9 | `restart-redpanda` | 58 s | event 75: CONFIRMED=62 |
| 10 | `pause-outbox-relay` | 52 s | event 76: CANCELLED=73 CONFIRMED=74; holds_expired_while_reserved +50 |
| 11 | `kill-redis-primary` | 59 s | event 77: CONFIRMED=65; killed redis-2, 3 section rebuilds |
| 12 | `kill-inventory` | 61 s | event 78: CONFIRMED=49 |
| 13 | `payment-failures` | 47 s | event 79: CANCELLED=6 CONFIRMED=63; 6 cancelled |
| 14 | `payment-timeouts` | 64 s | event 80: CANCELLED=14 CONFIRMED=62; 14 late successes refunded |
| 15 | `late-success-resale` | 56 s | event 81: CANCELLED=8 CONFIRMED=12; 9 seats resold, their late payers refunded |
| 16 | `duplicate-callbacks` | 46 s | event 82: CONFIRMED=62 |
| 17 | `partition-order-postgres` | 47 s | event 83: CONFIRMED=68 |
| 18 | `slow-order-postgres` | 46 s | event 84: CONFIRMED=67 |
| 19 | `restart-redpanda` | 46 s | event 85: CONFIRMED=65 |
| 20 | `pause-outbox-relay` | 61 s | event 86: CANCELLED=67 CONFIRMED=79; holds_expired_while_reserved +33 |

## What the runs found

| Finding | Seen as | Fix |
|---|---|---|
| Inventory answered **500** when a Redis connection reset mid-failover | k6 `unexpected_status` in `kill-redis-primary` | `RedisException` → `503 RETRY_LATER`, `Retry-After: 1` |
| Order answered **500** when Postgres was unreachable | 16 unexpected statuses in `partition-order-postgres` | `@RestControllerAdvice`: connection-level `DataAccessException`s → `503 RETRY_LATER` |
| Redis Cluster stayed in `cluster_state:fail` after every node restarted with a new IP | cluster-init hung after a Docker daemon restart | `CLUSTER MEET` at current addresses while waiting (merged separately) |
| Resale race didn't happen: seats sold out before the timeout storm started | `late-success-resale` expectation failed twice (0 violations) | inject the storm before the first buyer |
| Relay pause produced no lapsed hold: no confirmation landed during the pause | `pause-outbox-relay` expectation failed twice (0 violations) | 2-4 s charges during that scenario, more seats |

None of the runs, including the failed ones, ever had an invariant violation: no seat
sold twice, no capacity exceeded, no confirmed order without a captured payment and
tickets, no late success left unrefunded. The bugs were in how failures were
*reported*, which is exactly what a chaos run under real client traffic surfaces and a
Reconciler can't.

## Load without faults (warm stack)

k6 `buyer.js`, 60 s, 4,000-seat event (10 sections, half the buyers on section A):

| Buyers/s | HTTP req/s | Confirmed orders | Hold p50 / p95 / p99 | Checkout p50 / p95 / p99 | Unexpected statuses |
|---:|---:|---:|---|---|---:|
| 10 | 72 | 471 | 3 / 12 / 651 ms | 17 / 33 / 52 ms | 0 |
| 30 | 219 | 1,046 | 3 / 24 / 108 ms | 20 / 106 / 281 ms | 0 |

At 30/s the hot section sells out partway through (611 buyers found no seats), as
intended by the skew. Admission wait (~0.5 s) and order settle (~1 s) are the k6
client's poll intervals, not server time.

**Cold start.** The first load run after a restart showed checkout p95 2.7 s. The same
load on a warm stack: 33 ms. The Java services start with the OpenTelemetry agent and
an unwarmed JIT; the first minute of traffic pays for it. A real sale should open on
warm instances (week 6's headline test warms up before measuring).
