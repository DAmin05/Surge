# 0004 — Seat events are published without an outbox; the map self-heals

**Status:** Accepted

## Context

Inventory publishes a `SeatEvent` for every seat change so the live map can flip seats
the moment someone holds them. Order publishes its events through a transactional
outbox, which makes publication exactly as durable as the state change. Inventory can't:
it has no database, and its state lives in Redis, which has no transaction that spans
"write the hold" and "write to Kafka".

Options:

1. Put an outbox list in Redis next to the section, relayed to Kafka. That is still
   Redis: a failover loses the outbox entries along with the holds they describe.
2. Make Postgres the source of seat events. That puts every hold on the database path,
   which is exactly what Redis is there to avoid (ADR 0001).
3. Publish directly, accept that an event can be lost, and make consumers detect loss.

## Decision

Option 3.

- The producer is as reliable as a direct producer gets: `acks=all`,
  `enable.idempotence=true`, keyed by `eventId:section` so a section's events stay in
  order on one partition.
- **Every seat change carries `(section, epoch, seq)`**, where `seq` is incremented by
  the same Lua script that made the change. So `seq` is gap-free in Redis even when
  delivery isn't.
- Clients load a **snapshot** (`epoch`, `seq`, held and sold seats) on connect, buffer
  events while it loads, then apply only events with the same epoch and a higher seq.
  A gap or an epoch change triggers a new snapshot.

## Consequences

- The map can miss an event but can't stay wrong: the next event for that section
  reveals the gap and the client re-snapshots.
- Nothing correctness-critical consumes `seat-events`. The sale's guarantees never
  depend on it, only on the Postgres claim.
- Every code path that changes a seat must go through a script that increments `seq`.
  A path that skips it causes false gaps (spurious re-snapshots), which show up in the
  gateway's re-snapshot rate.
- After a Redis failover, `seq` may restart; the new random epoch tells clients to
  discard what they have.
