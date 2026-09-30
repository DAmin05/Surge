# 0007 — Trace context is stored in the outbox row and restored by the relay

**Status:** Accepted

## Context

The week-4 exit criterion is one trace spanning every service a checkout touches. Most
hops propagate context for free: the OpenTelemetry agent covers HTTP, gRPC, JDBC,
Lettuce and Kafka producers in the Java services, and the gateway injects
`traceparent` into its upstream calls. The outbox breaks the chain. Order writes the
event in the checkout transaction, and a relay thread publishes it later; by then the
request's context is gone, so Kafka records (and everything downstream: Payment, the
webhook, the sold marker) would start new traces.

## Decision

- `outbox.headers JSONB` (migration V3). `Outbox.append` injects the current context
  with the global propagator (`traceparent`, `tracestate`, baggage) into that column,
  in the same transaction as the event.
- The relay extracts the context from each row and publishes inside it, so the
  producer span is a child of the original checkout span, and the agent writes that
  context into the Kafka record headers.
- Payment continues the context from record headers: consumer span → charge span →
  webhook call (`propagate.inject`). Order's webhook handler and its outbox writes
  continue the same trace, so `OrderConfirmed` and Inventory's sold marker join it too.
- `scripts/trace-check.sh` is the check: buy through the gateway, take `x-trace-id`,
  wait for CONFIRMED, and require gateway, order, inventory and payment in that trace.

## Consequences

- One trace shows the whole saga, including the relay delay (the gap between the
  `INSERT outbox` span and `order-events publish`) and Payment's latency.
- Traces are longer-lived than requests: a trace may stay open until the payment
  timeout T. Jaeger handles this; sampling decisions are made at the gateway root, so
  a sampled checkout stays sampled end to end.
- A row written without an active context gets `{}` and publishes as a new trace.
  That is correct for background work (sweeper timeouts, refunds).
- Seat events have no outbox (ADR 0004); their producer spans are already in the
  request's context.
