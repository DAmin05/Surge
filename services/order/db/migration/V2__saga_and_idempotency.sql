-- Week 2: persisted saga history, links needed by compensation, idempotency leases.

-- Every state change, in order. The saga is an explicit state machine; this is its log.
CREATE TABLE order_transitions (
  id          BIGSERIAL PRIMARY KEY,
  order_id    BIGINT NOT NULL REFERENCES orders(id),
  from_state  TEXT,
  to_state    TEXT NOT NULL,
  reason      TEXT NOT NULL,
  at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX order_transitions_order ON order_transitions (order_id, id);

-- The Redis hold behind the order: Inventory deletes it (sold) or releases it
-- (cancelled) when the saga reaches a terminal state.
ALTER TABLE orders ADD COLUMN hold_id TEXT;

-- Set once when a payment succeeds after the order was already cancelled, so the
-- refund is requested exactly once however many late callbacks arrive.
ALTER TABLE orders ADD COLUMN refund_requested_at TIMESTAMPTZ;

-- Whoever holds the lease may complete an in-progress key. A retry that takes over a
-- stuck key gets a new token, so the stuck request can no longer commit a response.
ALTER TABLE idempotency_keys ADD COLUMN lease_token UUID;
