# Architecture decision records

Short records of decisions that shaped Surge, so the reasoning survives the code.
One file per decision, numbered, never rewritten: a later ADR supersedes an earlier one.

| # | Decision | Status |
|---|---|---|
| [0001](0001-postgres-is-the-source-of-truth.md) | Postgres, not Redis, is the source of truth for seat ownership | Accepted |
| [0002](0002-schema-per-service.md) | One Postgres, one schema and role set per service | Accepted |
| [0003](0003-token-signing-keys.md) | Ed25519 tokens, keys generated once, `kid` keysets for rotation | Accepted |
| [0004](0004-seat-events-without-outbox.md) | Seat events are published without an outbox; the map self-heals | Accepted |
| [0005](0005-orchestrated-saga.md) | The checkout saga is orchestrated by Order, not choreographed | Accepted |
| [0006](0006-gateway-consumes-every-partition.md) | Every gateway reads every seat-events partition, with a pure-Rust client | Accepted |
| [0007](0007-trace-context-in-the-outbox.md) | Trace context is stored in the outbox row and restored by the relay | Accepted |
| [0008](0008-single-origin-through-the-gateway.md) | The gateway serves the frontend: one origin for pages, API and WebSocket | Accepted |
| [0009](0009-chaos-judges-correctness-after-settling.md) | Chaos runs judge correctness after the system settles, not availability during the fault | Accepted |

Template: **Context** (what forces the decision) → **Decision** → **Consequences**
(what gets easier, what gets harder, what we'll watch).
