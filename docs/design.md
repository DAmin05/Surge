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
| frontend | Next.js / TypeScript | buyer flow, war room, chaos panel (served through the gateway) |

Java: Spring Boot, `spring.threads.virtual.enabled=true`, Gradle Kotlin DSL,
multi-module, all versions in `gradle/libs.versions.toml`. The only shared module is
`libs/contracts` (generated proto stubs + event DTOs). No shared business-logic
module: each service owns its outbox and idempotency code.

## Identity and tokens

- **Anonymous identity is server-issued.** On first visit (`POST /api/session`)
  Admission creates `userId` and returns it in a signed, HttpOnly, SameSite=Lax session
  cookie `surge_session` (Ed25519 JWT, 24 h). A valid existing cookie is kept. Clients
  never pick their own id. k6 obtains identities through the same endpoint.
- **Admission token:** Ed25519 JWT with claims `sub`, `eventId`, `exp`, `jti`; header
  `kid`. `exp` is 10 minutes after *admission* (not after issue), so polling again
  yields an equivalent token.
- **Token type lives in the JWT header** (`typ: surge-session+jwt` /
  `surge-admission+jwt`, RFC 8725 explicit typing); `alg` must be `EdDSA`. Signed with
  the JDK's Ed25519; verified in the gateway with `ed25519-dalek` (`verify_strict`).
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

- Keys share the hash tag `{adm:evt:<id>}`: `queue` (zset, arrival time), `admitted`
  (zset, admission time), `bucket` (rate limiter state).
- `POST /api/queue/{e}/join`: `ZADD NX` → one queue position per user per event;
  already admitted → the token.
- `GET /api/queue/{e}/position`: `WAITING` with position, `ahead` and an estimated wait,
  or `ADMITTED` with the token, or `404 NOT_IN_QUEUE`.
- **Admitter:** every 100 ms, a Lua script pops users into `admitted` through a **token
  bucket stored in Redis** (`ADMIT_RATE_PER_SEC`, burst = 1 s). The rate is global
  however many Admission instances run, and moving a user from queued to admitted is
  one atomic step, so a crash can't lose anyone. Tokens are minted on read from the
  admitted set: no "admitted but no token" state exists. Admissions older than the
  token lifetime are pruned; that user may queue again.
- Metrics: `queue_depth{event}`, `queue_joins_total`, `queue_admitted_total`.

## Gateway

Every request passes a per-IP token bucket (`RATE_LIMIT_IP_PER_SEC`, burst 2x);
authenticated routes also a per-user one. The gateway strips any client-sent
`X-User-Id` and sets it from the verified token.

| Route | Token | Upstream |
|---|---|---|
| `POST /api/session` | none | Admission `/session` (cookies pass both ways) |
| `POST /api/queue/{e}/join`, `GET /api/queue/{e}/position` | session cookie | Admission |
| `GET /api/events` | none | Order: events on sale |
| `GET /api/events/{e}` | none | Order catalog (sections, prices, seats), cached 30 s |
| `GET /api/orders/{id}` | session cookie | Order: the caller's own order (404 for anyone else's) |
| `POST /api/holds` | admission, `eventId` in body must match | Inventory |
| `DELETE /api/holds/{id}`, `POST /api/checkout` | admission, hold id's event must match | Inventory / Order |
| `GET /ws/events/{e}` | none (per-IP connect limit) | live seat map |
| anything else | none (per-IP limit) | frontend (`FRONTEND_URL`), cookies and identity headers stripped |

The browser sees one origin: pages, API and WebSocket all come from the gateway, so
there is no CORS and the HttpOnly session cookie just works
([ADR 0008](adr/0008-single-origin-through-the-gateway.md)). Every response carries
`x-trace-id`, the trace the request started.

**Seat map protocol (`/ws/events/{e}`):** the gateway subscribes the socket to the
event's broadcast channel *before* taking the snapshot, then sends
`{"type":"snapshot","eventId":e,"sections":[{section,epoch,seq,sold,held}]}` followed by
`{"type":"seat","event":<SeatEvent>}` frames. The client sends
`{"type":"resnapshot","section":"A"}` (or no section) on a seq gap or epoch change. A
client too slow for the channel's buffer gets a fresh full snapshot. Snapshots are
cached 250 ms with request coalescing, so a reconnect storm isn't a request storm; a
slightly stale snapshot only costs the client one re-snapshot. Kafka consumption:
[ADR 0006](adr/0006-gateway-consumes-every-partition.md).

Metrics: `gateway_ws_connections`, `gateway_seat_event_age_at_fanout_seconds`,
`gateway_upstream_seconds{route}`, `gateway_rate_limited_total{by}`,
`gateway_ws_lagged_total`, `gateway_ws_resnapshots_total`.

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
in progress → `409 REQUEST_IN_PROGRESS`. Same key with a different `request_hash` →
`422 IDEMPOTENCY_KEY_REUSED`. Completed → the stored response is replayed
(`Idempotent-Replayed: true`; bodies come back from `jsonb`, so formatting is
normalized). Nightly job deletes rows older than 24 h.

- A `201` is stored **inside the claim transaction**, so "order exists" and "response
  stored" can't disagree. Rejections (`409`/`410`/`403`/`422`) are stored too; `503`s are
  not (the key is freed so a retry can proceed).
- A request that dies mid-flight would leave its key in progress forever, so a retry may
  **take over** a key that's been in progress for 60 s. Every attempt holds a
  `lease_token`; only the current holder can complete the key. If the stuck request
  wakes up, its completion matches no row and its whole claim rolls back.

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
forward (`OrderState` refuses anything else) and every one is recorded in
`order_transitions` with a reason. The claim transaction walks
`CREATED → SEAT_RESERVED → PAYMENT_PENDING` and writes `PaymentRequested` to the outbox,
so an order becomes pending exactly when its payment request is committed. A lost claim
rolls back completely and leaves no order row. `FAILED` is reserved; nothing writes it
today. Orchestration, not choreography: [ADR 0005](adr/0005-orchestrated-saga.md).

**Lock order (every code path):** per-(event, user) advisory lock → order row
(`FOR UPDATE`) → seat rows in ascending id (`FOR UPDATE`). The claim takes the advisory
lock and seats; confirmation and compensation take the order row and seats.

**Checkout responses:** `201` order created · `409 SEAT_TAKEN | USER_LIMIT` ·
`422 INVALID_SEATS | UNKNOWN_SECTION` · `410 HOLD_EXPIRED` · `403 NOT_YOUR_HOLD` ·
`503 RETRY_LATER` (Inventory unreachable or section rebuilding; `Retry-After: 1`).

**Payment request/response:**

- Request: outbox `PaymentRequested` → Payment consumes it,
  `INSERT … ON CONFLICT (payment_key) DO NOTHING`, charges only if it inserted. The
  mock charge is itself idempotent by `payment_key` (Kafka is at-least-once): settling
  is `UPDATE … WHERE status = 'PENDING'`. On restart Payment resumes `PENDING` charges.
- Webhook: `POST /payments/webhook`, body per `schemas/payment-webhook.schema.json`,
  signed `Surge-Signature: sha256=<hex HMAC-SHA256(secret, raw body)>` (a forged
  success would issue free tickets). Retried with backoff until Order answers 2xx or
  404, for up to 60 s; after that the timeout sweeper's `GET` covers it.
- Faults (runtime, `PUT /faults`): `failure_rate`, latency range, `timeout_rate` (the
  charge stalls `timeout_delay_s`, then succeeds: a late success), `duplicate_rate`
  (2-3 concurrent deliveries), `out_of_order_rate` (a stale `FAILED` after a success).
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
consumes it, first records the seats on the compacted `seat-status` topic (keyed by
`seat_id`, waits for the ack), then in one script writes sold markers (no TTL), deletes
the hold and its expiry entry, and emits `SEAT_SOLD`. A redelivery skips seats already
sold. If the hold was already gone or re-held when the sale landed,
`holds_expired_while_reserved` counts it. `OrderCancelled` releases the hold.
Inventory's consumer commits offsets only after a batch is applied.

**No resale:** a seat that had a ticket issued never goes back on sale. Upgrade path:
`tickets.status` + partial unique index on `seat_id WHERE status = 'ACTIVE'`. Seats of
orders that never confirmed return to `AVAILABLE`.

**Outbox relay:** polls every 200 ms, claims batches with `SELECT … FOR UPDATE SKIP
LOCKED` (safe with multiple relays), marks rows published only after the broker acks.
At-least-once; with several relays, one order's events may be published out of order,
so consumers are idempotent and decide by state, not arrival order. Lag:
`outbox_oldest_unpublished_seconds`.

## Live seat map

- Gateway subscribes to `seat-events` and fans out per event via
  `tokio::sync::broadcast`.
- On WebSocket connect the gateway sends a snapshot with `(epoch, seq)` per section.
- The client buffers events that arrive while a snapshot is in flight, then applies
  only those with the same epoch and a higher `seq`. A `seq` gap or an epoch change →
  request a new snapshot. The map can lose an event but cannot stay wrong.

## Frontend

Next.js (App Router, standalone output), reached only through the gateway.

- **Buyer flow** (`/events/{e}`): session → waiting room (polls position every 1 s;
  the seat map is live while waiting) → pick ≤ 4 seats in one section → hold with a
  countdown → checkout with one `Idempotency-Key` per hold, retried with the same key
  on 5xx, network errors and `REQUEST_IN_PROGRESS` → poll the order until terminal.
  Seats someone else takes drop out of the selection as the events arrive.
- **Seat map client** (`frontend/lib/seatmap.ts`, unit-tested): the protocol under
  *Live seat map*; re-renders at most once per animation frame.
- **War room** (`/war-room`): `/x/metrics` queries Prometheus and the chaos log
  server-side and returns one JSON document. Shows the reconciler's violation count as
  the headline, orders by saga state, KPIs, checkout p50/p99, fan-out p99 against the
  200 ms goal, and throughput; chaos actions are shaded bands on every time series.
- **Chaos panel** (`/chaos`): runs and stops chaos actions via `/x/chaos/*`, a proxy
  that passes only the controller's own routes.

## Observability

- **Traces:** OTLP to Jaeger. Java services use the OpenTelemetry agent (version in the
  catalog); the gateway uses `tracing-opentelemetry`; Payment instruments FastAPI,
  its Kafka consumer, the charge and the webhook call.
- **Through the outbox:** the trace context is written into `outbox.headers` in the
  same transaction as the event, and the relay publishes inside that context, so a
  checkout trace continues through Kafka into Payment, back through the webhook and
  on to Inventory's sold marker
  ([ADR 0007](adr/0007-trace-context-in-the-outbox.md)).
- **Metrics:** Prometheus scrapes every service. Order adds `orders_by_state{state}`;
  the chaos controller exports `chaos_actions_total` and `chaos_active`.

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

Runs every 5 s during a sale (`reconciler_invariant_violations{invariant}`,
`GET /violations`) and on demand (`POST /audit`, `make audit`). Postgres checks run as
`reconciler_ro`; invariant 5 reads the Redis expiry sets. Tolerances: invariant 3 allows
a captured payment on a cancelled order `REFUND_GRACE` (60 s) to be refunded and
ignores orders still `PAYMENT_PENDING` (that's invariant 4's job); invariant 4 flags
orders open past T + one timeout-sweeper pass + 15 s slack.

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
- WebSocket fan-out (`make ws-bench`): 10,000 clients on one event, 100 seat
  changes at 20/s; every client must receive every change, p99 < 200 ms from
  Inventory's change to the client's read. Judged locally at 10k; CI guards at 5k with
  the same budget ([results](results/week3-websocket-fanout.md)).
- Saga storm (`make saga-storm`, CI): buyers vs. a faulty Payment (30 % declines,
  15 % stalls past T, 30 % duplicate and 25 % out-of-order callbacks), same-key
  checkout retries; every order must end terminal with 0 violations.
- Chaos scenarios: kill a Redis primary mid-sale (lost holds + sold-set rebuild), kill
  an inventory instance, payment timeout storm, duplicate/out-of-order webhooks,
  Order↔Postgres partition, Redpanda restart during relay, late success after
  release-and-resale (refund), relay paused past grace (`holds_expired_while_reserved`).
- Chaos controller (`chaos`, `GET /actions`, `POST /actions/{id}` with an optional
  `durationS`, `DELETE /actions/{id}`, `POST /reset`, `GET /events`): kill the Redis
  primary, kill inventory, payment failures/timeouts/duplicate callbacks, partition or
  slow Order↔Postgres (Toxiproxy), restart Redpanda. Timed actions undo themselves.

## Deployment

Single VM (Hetzner/EC2) with Compose. Everything configured via environment variables.
Images are multi-arch (`docker buildx bake`, linux/amd64 + linux/arm64).

## Delivery

One PR per week into `main`, merged before the next starts. Each PR states its exit
criterion, how CI checks it, and ADRs added. The exit test is a required CI check.
