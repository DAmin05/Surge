# 0001 — Postgres, not Redis, is the source of truth for seat ownership

**Status:** Accepted

## Context

A flash sale is thousands of buyers racing for the same seats within seconds. Seat
holds must be fast, so they live in Redis: an atomic Lua check-and-set per section,
with a lease. But Redis Cluster replicates **asynchronously**. If a primary acknowledges
a hold and dies before the replica receives it, the promoted replica has never heard of
that hold, and a second buyer can hold the same seat. With holds as the only record,
that becomes an oversell: two tickets for one seat.

We can't make Redis synchronous (`WAIT` narrows the window but doesn't give
linearizability across failover), and we don't want every hold to be a Postgres write:
holds are the hottest path and most of them never turn into a purchase.

## Decision

Split the job:

- **Redis is the performance layer.** It absorbs the herd: most contention and every
  abandoned hold stay in Redis, and it drives the live seat map.
- **Postgres is the correctness layer.** At checkout, one transaction claims every
  seat in the order with a conditional update:

  ```sql
  SELECT ... FROM seats WHERE id = ANY(:ids) ORDER BY id FOR UPDATE;
  UPDATE seats SET status = 'RESERVED', order_id = :order
   WHERE id = ANY(:ids) AND status = 'AVAILABLE';
  -- fewer than N rows updated -> roll back; the order fails before any charge
  ```

  and `tickets.seat_id` is `UNIQUE`, so even a bug above this layer cannot issue two
  tickets for one seat.

The guarantee is placed deliberately where it can actually be enforced.

## Consequences

- A Redis failover can produce two buyers who both *believe* they hold a seat. Exactly
  one wins the claim; the other gets a fast, clean failure and is never charged. This is
  a chaos scenario we run, not a bug we hide.
- Every change to the hold path has to preserve "the claim is the decision". Code that
  treats a Redis hold as proof of ownership (e.g. skipping the claim) is a defect.
- Seats are never resold once a ticket is issued. The upgrade path, if ever needed, is a
  `tickets.status` column plus a partial unique index on `seat_id WHERE status = 'ACTIVE'`.
- The Reconciler verifies the invariants (no seat sold twice, sold ≤ capacity, …)
  continuously anyway: constraints prevent the bug, the audit proves it.
