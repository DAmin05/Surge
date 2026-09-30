# 0005 — The checkout saga is orchestrated by Order, not choreographed

**Status:** Accepted

## Context

After the seat claim, checkout spans three services: Order (the claim and tickets),
Payment (the charge), and Inventory (the Redis holds). Each step can fail, arrive twice,
or arrive late, and every failure needs a compensation: release the seats, refund a
charge that landed after cancellation.

Two ways to coordinate:

- **Choreography:** each service reacts to the others' events. Payment charges on
  `SeatReserved`, Order confirms on `PaymentSucceeded`, Inventory releases on
  `PaymentFailed`, and so on. No coordinator, but the saga's state lives nowhere; it has
  to be reconstructed from event history across three services.
- **Orchestration:** one service owns the saga as an explicit, persisted state machine
  and tells the others what to do.

## Decision

Order orchestrates. The saga is `orders.state` plus `order_transitions`, and:

- Transitions only move forward, and the code refuses anything not in the state table.
- Every decision is made under a row lock on the order, from its current state. That's
  what makes duplicate, late and reordered callbacks safe: a late `FAILED` after
  `CONFIRMED` is simply a no-op, and a late success after `CANCELLED` becomes one refund.
- Commands go out through Order's transactional outbox (`PaymentRequested`,
  `RefundRequested`, `OrderConfirmed`, `OrderCancelled`), in the same transaction as the
  state change that caused them.
- The payment result comes back through **one** authoritative channel, the signed
  webhook. Payment's Kafka events exist for observers (Reconciler, dashboard) and never
  drive the saga, so there's no second source of truth to disagree with.
- Timeouts are Order's job too: a sweeper moves orders stuck in `PAYMENT_PENDING` past T
  to compensation, after asking Payment for the truth.

## Consequences

- "Where is order 1234 and why?" is one query: its row and its transitions.
- Compensation logic lives in one place (`PaymentOutcomes`), with every case explicit
  and tested, including the races between them.
- Order is a coordination point. That's acceptable: it already holds the correctness
  guarantee (the claim), and it scales with its database, not with the herd, which the
  waiting room and Redis absorb upstream.
- Inventory and Payment stay simple and idempotent: they follow commands and don't need
  to know the saga.
