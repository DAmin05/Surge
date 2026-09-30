# 0010 — Redis clients fail fast during a failover; replicas are kept replicating

**Status:** Accepted

## Context

Inventory (holds) and Admission (the waiting room) live on Redis Cluster. When a
primary dies, its replica should take over within seconds, and requests for its slots
should fail briefly and then succeed against the new primary. The headline chaos run
showed confirmed checkouts at **zero for ~25 s**, the whole time the primary was down,
with every correctness check still passing.

Two things were wrong:

- **No replica was promoted.** After every Redis container restarted with a new IP
  (Docker daemon restart; the same as a host reboot), `CLUSTER MEET` repaired the
  cluster bus, but replicas kept replicating from their primaries' old addresses
  (`master_link_status:down`). Redis never promotes a replica that isn't in sync.
- **Lettuce defaults queue commands for a dead node.** While disconnected, a
  connection accepts commands and replays them after reconnecting; reconnects back off
  exponentially, and the default adaptive topology refresh waits for several failed
  reconnects. Callers wait instead of failing and retrying against the new primary.

## Decision

- `redis-cluster-init` re-points any replica whose link is down at its primary's
  current address (`CLUSTER REPLICATE <primary id>`) and waits until every replica is
  replicating. It already re-introduces nodes with `CLUSTER MEET`; together that makes
  the cluster recover from a full IP reshuffle with failover intact.
- Lettuce cluster clients (Inventory, Admission):
  `DisconnectedBehavior.REJECT_COMMANDS`, a 2 s command timeout, periodic topology
  refresh every 5 s, and adaptive refresh after 2 failed reconnects (1 s rate limit).
- Every Redis failure surfaces as `503 RETRY_LATER` with `Retry-After: 1` (Inventory
  since week 5, Admission now). Clients retry; the checkout retries with the same
  Idempotency-Key.

## Consequences

- Killing a primary under load: the replica is promoted in ~9 s, checkouts dip and
  never stop, no buyer gives up (was: ~25 s at zero, 3 buyers gave up).
- During those seconds buyers see 503s instead of slow responses. That is the
  contract, and k6 and the frontend already retry them.
- A command that is slow for a reason other than failover now fails after 2 s instead
  of 60 s. Every Redis call in the hot path is a single-key or single-slot script well
  under a millisecond, so 2 s only fires when a node is unreachable.
- Correctness is unchanged: Postgres still decides every seat
  ([ADR 0001](0001-postgres-is-the-source-of-truth.md)).
