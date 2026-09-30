# schemas

JSON Schemas for Kafka event payloads (topics: [docs/design.md](../docs/design.md#kafka-topics)).
The Java DTOs in `libs/contracts` must match these.

| Schema | Topic |
|---|---|
| [seat-event](seat-event.schema.json) | `seat-events` |
| [order-event](order-event.schema.json) | `order-events` |
| [payment-event](payment-event.schema.json) | `payment-events` |
| [seat-status](seat-status.schema.json) | `seat-status` (compacted) |
| [payment-webhook](payment-webhook.schema.json) | HTTP: Payment → Order `/payments/webhook` |
