# Surge

A flash-sale ticketing system built to survive the herd **without ever overselling**:
a waiting room, seat holds, a checkout saga with payments, and a chaos suite that tries
to break it while a reconciler proves it didn't.

**The core decision:** Redis holds are the performance layer; Postgres is the
correctness layer. Redis Cluster replicates asynchronously, so a failover can lose an
acknowledged hold and two buyers can both think they hold a seat. A conditional
multi-row claim in Postgres, backed by a unique constraint on tickets, means exactly one
wins, and the loser is compensated before being charged.
→ [ADR 0001](docs/adr/0001-postgres-is-the-source-of-truth.md) ·
[full design](docs/design.md)

```
Browser ──► Gateway (Rust/Axum) ──► Admission · Inventory · Order (Java 21)
                 ▲  WebSocket              │ Redis Cluster     │ Postgres ─ outbox ─► Redpanda
                 └──────────── seat-events ◄───────────────────┴──────────────────────┘
                                   Payment · Reconciler · Chaos (Python)
```

## Quick start

Requires Docker (Docker Desktop: give it at least 8 GB of RAM).

```bash
make up        # build images, start ~25 containers, wait until all are healthy
make help      # everything else
```

| What | Where |
|---|---|
| Gateway | http://localhost:8080 |
| Grafana | http://localhost:3001 |
| Prometheus | http://localhost:9090 |
| Jaeger | http://localhost:16686 |
| Chaos controller | http://localhost:8002 |
| Postgres | `localhost:5432` (`surge` DB) |
| Redpanda (Kafka API) | `localhost:19092` |

Configuration is environment variables only; defaults are in [`.env.example`](.env.example).
Token signing keys are generated once into `secrets/` (gitignored) by `make keys` or
automatically on first `up`.

## Layout

```
gateway/            Rust (Axum): token verify, rate limit, proxy, WebSocket fan-out
services/           Java 21, Spring Boot, Gradle multi-module
  admission/        waiting room + tokens
  inventory/        Redis holds, sweeper, seat events
  order/            checkout, claim, saga, outbox   (db/migration: orders schema)
libs/contracts/     generated gRPC stubs + event DTOs only
payment/            Python/FastAPI mock gateway    (db/migration: payments schema)
reconciler/         Python: invariant checks
chaos/              Python: Docker SDK + Toxiproxy
frontend/           Next.js (week 4)
proto/              gRPC contracts
schemas/            event JSON schemas
loadtest/           k6 scenarios (week 5)
infra/              compose files, Postgres bootstrap, Redis/Redpanda init, observability
docs/               design spec, ADRs, results
```

## Roadmap

Each phase ships as one PR whose exit criterion is a required CI check.

- [ ] **Week 0**: monorepo, Compose stack, Flyway migrations, CI skeleton.
  *Exit: `docker compose up` brings everything healthy.*
- [ ] **Week 1**: Inventory (Lua holds, sweeper) + Order happy path + seat claim.
  *Exit: 1,000 virtual threads race for 1 seat → exactly 1 winner.*
- [ ] **Week 2**: Payment mock, saga, outbox relay, idempotency, Reconciler v1.
  *Exit: same checkout retried 100× → 1 order; injected failures all end terminal.*
- [ ] **Week 3**: Admission, Rust gateway (tokens, rate limit, WS fan-out).
  *Exit: 10k WebSocket clients get seat updates < 200 ms p99 locally.*
- [ ] **Week 4**: Frontend (buyer flow, war room, chaos panel), OTel, metrics.
  *Exit: full buyer flow in the browser; one trace spans all services.*
- [ ] **Week 5**: k6 scenarios, chaos scenarios, tuning.
  *Exit: 20 chaos runs, 0 invariant violations.*
- [ ] **Week 6**: headline load test, results, demo video, deployment.
  *Exit: published numbers, graphs and ADRs.*
