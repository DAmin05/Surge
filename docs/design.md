# Surge — design spec

This is the agreed design. Code must match it; if the code needs to diverge, change
this file (and add an ADR when the change is a real decision) in the same PR.

## The one guarantee

**Redis is the performance layer. Postgres is the correctness layer.**

Redis Cluster replicates asynchronously, so a primary failover can lose an
acknowledged hold and two buyers can briefly both "hold" the same seat. Postgres
decides: a conditional multi-row claim plus a unique constraint on `tickets.seat_id`
means exactly one order wins, and the loser is compensated before it is ever charged.
See [ADR 0001](adr/0001-postgres-is-the-source-of-truth.md).

## Services

| Service | Lang | Owns |
|---|---|---|
| gateway | Rust (Axum/Tokio) | token verify, rate limits, proxy, WebSocket fan-out |
| admission | Java 21 / Spring Boot | anonymous identity, waiting room, admission tokens |
| inventory | Java 21 / Spring Boot | Redis holds, sweeper, seat events, sold set |
| order | Java 21 / Spring Boot | checkout, seat claim, saga, outbox, idempotency |
| payment | Python / FastAPI | mock gateway with fault injection, refunds |
| reconciler | Python | invariants, continuous + post-sale audit |
| chaos | Python | Docker SDK + Toxiproxy fault controller |

Java: Spring Boot, `spring.threads.virtual.enabled=true`, Gradle Kotlin DSL,
multi-module, all versions in `gradle/libs.versions.toml`. The only shared module is
`libs/contracts` (generated proto stubs + event DTOs). No shared business-logic
module: each service owns its outbox and idempotency code.

## Identity and tokens

- **Anonymous identity is server-issued.** On first visit Admission creates `userId`
  and returns it in a signed, HttpOnly session cookie (Ed25519 JWT, `typ=session`).
  Clients never pick their own id. k6 obtains identities through the same endpoint.
- **Admission token:** Ed25519 JWT, `typ=admission`, claims `sub`, `eventId`, `exp`
  (10 min), `jti`. Header carries `kid`.
- Session and admission tokens use **separate key pairs / `kid`s**. The gateway
  rejects any token whose `typ` does not match the route (no replaying a session token
  as an admission token).
- Tokens are multi-use until `exp`. `jti` is for tracing plus revocation: revoked
  `jti`s go on the `token-revocations` topic; every gateway instance keeps them in
  memory until the token's `exp`.
- **Keys are generated once** (`make keys` or the `keygen` one-shot container) into
  the gitignored `secrets/` directory, only if absent. The gateway loads a keyset with
  the current and previous key per token type, so rotation needs no downtime
  (`make rotate-keys`).

## Admission (waiting room)

- Redis sorted set per event, scored by arrival time. `ZADD NX` → one queue position
  per user per event.
- Scheduled admitter pops N users/sec (sized to downstream capacity) and issues
  admission tokens.
- `GET /queue/{eventId}/position`, polled or pushed over WebSocket.

## Inventory (holds)

- Keys use a per-section hash tag, e.g. `{evt:42:secA}:seat:17`, so a section lives
  on one shard and one Lua script can touch all seats of an order.
- **Orders are 1–4 seats, all in one section** (cross-section rejected at the API).
  The hold script holds all N seats atomically or none.
- Lease: 5 min. Expiry via a per-section expiry sorted set + sweeper (not keyspace
  notifications, which are lossy).
- **Every seat state change goes through a Lua script that `INCR`s the section's
  sequence**: hold, release, sweeper expiry, pin (`SeatHoldExtended`), sold marker.
  Events carry `(section, epoch, seq)`.
- **Epoch:** a random id per section stored in Redis. The hold script returns
  `REBUILDING` if the epoch key is missing, so "no holds until replay finishes" is
  atomic. On rebuild, Inventory replays the compacted `seat-status` topic to restore
  sold markers, then writes a *new random* epoch. Epochs are compared only for
  equality, never ordered.
- **Sold markers** have no TTL; the hold script rejects sold seats. A lost marker is
  still safe: a stale hold on a sold seat loses at the Postgres claim.
- **Per-user limit (best effort layer):** per-user sorted set of held seat keys scored
  by expiry; `ZREMRANGEBYSCORE` expired entries, then count ≤ 4. Different hash slot
  from the section, so not atomic with the hold — fine for a pre-check.
- Producer: `acks=all`, `enable.idempotence=true`. Seat events may still be lost
  (no outbox — Inventory has no database); the map self-heals (below).
- gRPC `ValidateAndPinHold(holdId, userId)` and `ReleaseHold` — see checkout ordering.
- Hold ids name their section (`<eventId>:<section>:<uuid>`), so any instance finds a
  hold's shard from the id alone. Section names match `[A-Za-z0-9_-]{1,32}`.
- Inventory doesn't validate that seat ids exist (it has no database). An unknown
  seat can be held but never claimed: the Postgres claim rejects it (`INVALID_SEATS`),
  and the per-user limit caps how much of that anyone can do.
- Key layout and script contracts: [`services/inventory/src/main/resources/lua`](../services/inventory/src/main/resources/lua/README.md).
- Internal REST (the gateway sets `X-User-Id`): `POST /holds`, `DELETE /holds/{id}`,
  `GET /sections/{eventId}/{section}/snapshot`.

## Checkout, claim, and saga (Order)

`POST /checkout` requires `Idempotency-Key`.

**Idempotency:** primary key `(user_id, key)`. Row with null `response_code` =
in progress → `409`. Same key with a different `request_hash` → `422`. Nightly job
deletes rows older than 24 h.

**Checkout ordering:**

1. `ValidateAndPinHold` — one Lua script: every hold in the order exists and belongs
   to `userId`; extend each lease to `now + T + grace`, update the expiry-set scores,
   `INCR` per seat (emits `SeatHoldExtended`). All-or-nothing.
2. Postgres claim transaction (below).
3. If the claim fails, release the holds immediately (best effort; the lease expires
   on its own otherwise).

Crash after pin/before claim → seats blocked until `T + grace`, bounded and harmless.
Crash after claim → already pinned. No outbox needed for the pin.

**Claim transaction — lock order is fixed on every code path:**

1. `pg_advisory_xact_lock(event_id::int, hashtext(user_id))`
2. `SELECT … FROM seats WHERE id = ANY(?) ORDER BY id FOR UPDATE`
3. Per-user limit (authoritative): seats in the user's orders for this event whose
   state is not `CANCELLED`/`FAILED`, plus this order, must be ≤ 4.
4. Insert the order + `order_items` with `unit_price_cents` copied from
   `section_prices`; `orders.amount_cents` = sum of items. (The order row comes first
   because `seats.order_id` references it.)
5. `UPDATE seats SET status='RESERVED', order_id=? WHERE id = ANY(?) AND status='AVAILABLE'`;
   fewer than N rows → roll back the whole transaction, order fails fast, no charge.
6. Outbox `PaymentRequested`, same transaction.

**Saga states:** `CREATED → SEAT_RESERVED → PAYMENT_PENDING → CONFIRMED`;
compensation `PAYMENT_FAILED → SEAT_RELEASED → CANCELLED`. Transitions only move
forward. The claim transaction writes the order directly as `SEAT_RESERVED`; a lost
claim rolls back completely and leaves no order row (the checkout answers `409
SEAT_TAKEN`, and idempotency records that response). `CREATED` and `FAILED` remain in
the state set for orders recorded before or without a claim.

**Checkout responses:** `201` order created · `409 SEAT_TAKEN | USER_LIMIT` ·
`422 INVALID_SEATS | UNKNOWN_SECTION` · `410 HOLD_EXPIRED` · `403 NOT_YOUR_HOLD` ·
`503 RETRY_LATER` (Inventory unreachable or section rebuilding; `Retry-After: 1`).

**Payment request/response:**

- Request: outbox `PaymentRequested` → Payment consumes it,
  `INSERT … ON CONFLICT (payment_key) DO NOTHING`, charges only if it inserted. The
  mock charge is itself idempotent by `payment_key` (Kafka is at-least-once).
- Response: **the webhook to Order is authoritative** (Stripe-style). Kafka
  `Payment*` events are for observers only (Reconciler, dashboard).
- Late `failed` after `succeeded` → ignored. Late `succeeded` after a timeout
  cancellation → Order writes `RefundRequested` to its outbox → Payment refunds →
  `REFUNDED`.
- **Timeout sweeper** (interval 5 s) moves `PAYMENT_PENDING` orders older than T to
  compensation, but first calls `GET /payments/{payment_key}` so a dropped webhook
  never cancels a paid order.
- Seats of failed/timed-out orders are released in Postgres and Redis, and
  `SeatReleased` emitted, **only after the order reaches a terminal state**. Legal race
  (covered by a chaos scenario): A's seats released and bought by B, then A's payment
  succeeds late → A is refunded.

**T and grace:** T (default 2 min) is measured from `PAYMENT_PENDING`; the lease from
the pin (earlier), so the lease starts first and ends later. Grace (default 30 s) must
cover sweeper interval + `GET /payments` + outbox relay interval (1 s) + consumer lag.
Both configurable. Metric `holds_expired_while_reserved` should be 0; a chaos scenario
pauses the relay past the grace to show it moving.

**After confirmation:** Order publishes `OrderConfirmed` via the outbox. Inventory
consumes it, deletes the holds and their expiry-set entries, writes sold markers
(no TTL), and publishes `SeatSold` to the compacted `seat-status` topic keyed by
`seat_id`.

**No resale:** a seat that had a ticket issued never goes back on sale. Upgrade path:
`tickets.status` + partial unique index on `seat_id WHERE status = 'ACTIVE'`. Seats of
orders that never confirmed return to `AVAILABLE`.

**Outbox relay:** `SELECT … FOR UPDATE SKIP LOCKED`, safe with multiple relays.

## Live seat map

- Gateway subscribes to `seat-events` and fans out per event via
  `tokio::sync::broadcast`.
- On WebSocket connect the gateway sends a snapshot with `(epoch, seq)` per section.
- The client buffers events that arrive while a snapshot is in flight, then applies
  only those with the same epoch and a higher `seq`. A `seq` gap or an epoch change →
  request a new snapshot. The map can lose an event but cannot stay wrong.

## Data

Postgres 16, one database, **one schema per service**:

- `orders` — owned by `orders_owner` (migrations), used at runtime by `orders_app`
  (DML only).
- `payments` — owned by `payments_owner`, runtime `payments_app`.
- `reconciler_ro` — read-only on both schemas.
- Roles are created by a bootstrap migration run as the admin user; each service's
  migrations run as that service's owner role. Passwords come from environment
  variables. Migrations are Flyway for every schema (one-shot containers).

Schema: [`services/order/db/migration`](../services/order/db/migration),
[`payment/db/migration`](../payment/db/migration).

## Reconciler invariants (target: 0 violations every run)

1. Sold seats ≤ capacity.
2. No seat sold twice (unique constraint, verified anyway).
3. Every captured payment has a ticket for **every** seat of its order, or a refund.
   Partial ticketing is a violation.
4. No order in a non-terminal state past T (+ sweeper interval).
5. No Redis hold exists past its pinned lease plus the sweeper interval.
6. No user holds more than 4 seats per event in orders not `CANCELLED`/`FAILED`.
7. `orders.amount_cents` = sum of `order_items.unit_price_cents`.

## Kafka topics

| Topic | Key | Notes |
|---|---|---|
| `seat-events` | section | SeatHeld / SeatReleased / SeatHoldExtended / SeatSold, `(section, epoch, seq)` |
| `seat-status` | seat_id | compacted; SeatSold — source for sold-set rebuild |
| `order-events` | order_id | outbox: PaymentRequested, OrderConfirmed, OrderCancelled, RefundRequested |
| `payment-events` | payment_key | PaymentSucceeded / PaymentFailed / PaymentRefunded — observers only |
| `token-revocations` | jti | revoked admission/session tokens |

## Load and chaos

- Seed (`make seed`, [`infra/postgres/seed.sql`](../infra/postgres/seed.sql)): 1 event ×
  10 sections × 1,000 seats, configurable; a **skewed-demand** mode
  sends ~50% of traffic to one section (contention is per shard).
- Chaos scenarios: kill a Redis primary mid-sale (lost holds + sold-set rebuild), kill
  an inventory instance, payment timeout storm, duplicate/out-of-order webhooks,
  Order↔Postgres partition, Redpanda restart during relay, late success after
  release-and-resale (refund), relay paused past grace (`holds_expired_while_reserved`).

## Deployment

Single VM (Hetzner/EC2) with Compose. Everything configured via environment variables.
Images are multi-arch (`docker buildx bake`, linux/amd64 + linux/arm64).

## Delivery

One PR per week into `main`, merged before the next starts. Each PR states its exit
criterion, how CI checks it, and ADRs added. The exit test is a required CI check.
