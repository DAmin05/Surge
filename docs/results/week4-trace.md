# Week 4: one checkout, one trace

`scripts/trace-check.sh` (CI job *Exit: browser purchase, one trace*) buys one seat
through the gateway and asks Jaeger for the trace named in the checkout response's
`x-trace-id` header. A local run (Compose stack, one laptop-class VM):

```
TRACE OK: one checkout trace spans gateway order inventory payment
trace 47a2ac5e…: services = gateway inventory order payment; spans = 42
```

The span tree, offsets from the gateway span's start:

```
gateway   POST /api/checkout                          +0.0ms   39.8ms
  order     POST /checkout                            +2.4ms   37.9ms
    order     INSERT idempotency_keys                 +5.9ms    1.6ms
    order     InventoryService/ValidateAndPinHold     +7.8ms   12.3ms
      inventory ValidateAndPinHold                    +13.0ms   6.2ms
        inventory HGET / EVALSHA / ZADD               +14.3ms   1.0ms
        inventory seat-events publish                 +17.0ms   4.4ms
    order     claim: SELECT … FOR UPDATE, INSERT orders, UPDATE seats, INSERT order_items
    order     INSERT outbox (PaymentRequested)        +33.0ms   0.8ms
    order     order-events publish   (relay)          +61.4ms   3.7ms
      payment   order-events process                  +65.5ms   3.5ms
        payment   payment charge                      +69.2ms 120.9ms
          order     POST /payments/webhook            +171.1ms 17.3ms
            order     UPDATE seats, INSERT tickets, UPDATE orders, INSERT outbox
            order     order-events publish (relay)    +271.3ms  5.1ms
              inventory order-events process          +277.8ms  9.4ms
                inventory seat-status publish         +278.8ms  6.8ms
                inventory EVALSHA (sold marker)       +286.1ms  0.5ms
                inventory seat-events publish         +287.0ms  3.6ms
```

What it shows:

- The request path (gateway → order → inventory gRPC → Redis → back) is ~40 ms, with
  the Postgres claim taking about half.
- The outbox relay adds 25–90 ms per hop: it polls, so the gap depends on where in
  the poll interval the row lands. It's off the buyer's request path.
- Payment's mock charge (~120 ms) dominates the saga, as a real PSP would.
- The browser test (Playwright, `frontend/e2e`) checks the same flow from the UI: the
  buyer's order reaches CONFIRMED and a second browser sees the seat go held, then
  sold, without reloading.
