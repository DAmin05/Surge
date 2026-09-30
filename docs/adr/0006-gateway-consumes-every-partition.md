# 0006 — Every gateway reads every seat-events partition, with a pure-Rust client

**Status:** Accepted

## Context

Each gateway instance holds some of the WebSocket clients for every event. A seat
change on any partition may matter to a client on any instance. Two more constraints:
the fan-out path is the latency-critical one (the exit criterion is p99 < 200 ms from
change to screen), and the gateway image should stay small and quick to build on both
arm64 and amd64.

## Decision

- **No consumer group.** Every instance reads every partition of `seat-events`,
  starting from the latest offset. A consumer group would split partitions between
  instances, and each would miss events its own clients need.
- Starting from latest is right: a new client gets a snapshot first, and events from
  before the gateway started are already in that snapshot.
- If the stream errors (for example, a broker restart), the partition resumes right
  after the last offset it processed.
- **rskafka** (pure Rust) instead of `rdkafka`: no librdkafka C build, no cmake in the
  image. We only need plain partition consumption, which is what rskafka does. Fetches
  use `min_batch_size = 1`, so a single event is returned without waiting to fill a batch.
- Each event is parsed once, only for `eventId`, then wrapped as a WebSocket frame
  *once* (`{"type":"seat","event":<raw>}`, no re-serialization) and sent through a
  `tokio::sync::broadcast` channel per event. Every subscriber gets a reference-counted
  clone of the same bytes.

## Consequences

- Kafka read load grows with the number of gateway instances (N instances read every
  event N times). At seat-event volumes this is trivial; it would matter at 100+
  instances, where a two-tier fan-out (regional relays) would be the next step.
- No offsets are committed, so nothing needs cleaning up when an instance goes away.
- A lost or late event is handled by the client, which detects the seq gap and asks
  for a new snapshot (ADR 0004).
