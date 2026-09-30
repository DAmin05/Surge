-- Payment service schema. Runs as payments_owner with search_path = payments.

CREATE TABLE payments (
  payment_key   UUID PRIMARY KEY,
  order_id      BIGINT NOT NULL,
  amount_cents  BIGINT NOT NULL CHECK (amount_cents >= 0),
  status        TEXT NOT NULL
                CHECK (status IN ('PENDING','CAPTURED','FAILED','REFUNDED')),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX payments_order ON payments (order_id);
