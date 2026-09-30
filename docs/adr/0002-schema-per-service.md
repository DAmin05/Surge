# 0002 — One Postgres, one schema and role set per service

**Status:** Accepted

## Context

Order and Payment both need durable state, and the Reconciler must read both to check
invariant 3 ("every captured payment has tickets for every seat, or a refund"). Separate
database servers would be more realistic but cost memory on a laptop and on a single VM.
A shared schema would let services reach into each other's tables, which quietly
couples them.

## Decision

One Postgres instance, one schema per service, enforced with roles:

| Role | Can do |
|---|---|
| `<svc>_owner` | owns the schema; runs that service's Flyway migrations |
| `<svc>_app` | runtime: DML on its own schema only, no DDL |
| `reconciler_ro` | `SELECT` on both schemas; read-only transactions, 30 s statement timeout |

A bootstrap migration, run as the admin user, creates roles, schemas and default
privileges. Each service's migrations then run as its owner role. Passwords come from
environment variables (Flyway placeholders).

## Consequences

- A service physically cannot read or write another service's tables; cross-service
  data moves through APIs and events.
- Moving a service to its own database later is a connection-string change.
- The Reconciler gets a consistent cross-schema view without an ETL step.
- One Postgres is a shared failure domain. That's accepted: the chaos suite partitions
  Order from Postgres through Toxiproxy to exercise exactly this.
