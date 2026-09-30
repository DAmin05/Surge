# 0009 — Chaos runs judge correctness after the system settles, not availability during the fault

**Status:** Accepted

## Context

The week-5 exit criterion is 20 chaos runs with 0 invariant violations. A chaos run
breaks something on purpose while buyers are buying: Redis loses a primary, Postgres is
unreachable, Payment stalls or repeats itself. During the fault, requests fail, the map
can be behind and the Reconciler can see orders in flight. We need a pass/fail rule
that is strict about what matters (nobody oversold, nobody charged without a ticket)
and doesn't depend on timing luck.

## Decision

- **During the fault, availability is allowed to drop.** k6 accepts every status in
  the API contract: `409`, `410`, `429`, `503 RETRY_LATER`, gateway `502/504`, network
  errors. It does not accept a `500` or any other status outside the contract: a
  crash surfacing as a 500 fails the run. A retried checkout that yields a second
  order id fails the run.
- **After the fault, the system must converge.** The runner waits, with a bounded
  deadline, until every order is terminal, no charge is pending, every late success is
  refunded, no holds remain, and each section's Redis sold set equals the seats
  Postgres sold. A run that doesn't converge fails.
- **Then the Reconciler judges the data**: 0 violations, 0 check errors.
- **Each fault must have bitten**: declines produce cancellations, the timeout storm
  produces refunds, the resale race produces a refunded payer whose seat someone else
  bought, the paused relay moves `holds_expired_while_reserved`. A scenario whose
  fault had no effect proves nothing.
- Faults are injected through the chaos controller, the same API the chaos panel uses.
  Saga timings are shortened for runs (T = grace = 10 s, lease 20 s), so a run takes
  about a minute.

## Consequences

- The first runs found two real bugs that the Reconciler couldn't see: Inventory
  answered 500 when a Redis connection reset mid-failover, and Order answered 500 when
  Postgres was unreachable. Both are now `503 RETRY_LATER`.
- The Redis-equals-Postgres check makes "the map can lose events but can't stay wrong"
  a tested property, including across a primary failover.
- Scenarios are probabilistic: a resale race needs contention, a lapsed hold needs a
  confirmation to land while the relay is paused. The first sizing let seats sell out
  before the fault started, and those runs failed their expectation (with 0
  violations). The runner now injects the resale fault before the first buyer and
  slows charges during the relay pause, so the effect appears in every run; if one
  ever doesn't, the run fails loudly rather than passing vacuously.
- Twenty runs take about 20 minutes of wall time on one machine, so CI splits them over
  four runners.
