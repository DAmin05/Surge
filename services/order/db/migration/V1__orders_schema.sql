-- Order service schema. Runs as orders_owner with search_path = orders.

CREATE TABLE events (
  id            BIGSERIAL PRIMARY KEY,
  name          TEXT NOT NULL,
  starts_at     TIMESTAMPTZ NOT NULL,
  capacity      INT NOT NULL CHECK (capacity > 0)
);

CREATE TABLE section_prices (
  event_id      BIGINT NOT NULL REFERENCES events(id),
  section       TEXT NOT NULL,
  price_cents   BIGINT NOT NULL CHECK (price_cents >= 0),
  PRIMARY KEY (event_id, section)
);

CREATE TABLE orders (
  id            BIGSERIAL PRIMARY KEY,
  user_id       TEXT NOT NULL,
  event_id      BIGINT NOT NULL REFERENCES events(id),
  section       TEXT NOT NULL,
  state         TEXT NOT NULL
                CHECK (state IN ('CREATED','SEAT_RESERVED','PAYMENT_PENDING','CONFIRMED',
                                 'PAYMENT_FAILED','SEAT_RELEASED','CANCELLED','FAILED')),
  amount_cents  BIGINT NOT NULL CHECK (amount_cents >= 0),
  payment_key   UUID NOT NULL UNIQUE,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Per-user seat limit is counted inside the claim transaction.
CREATE INDEX orders_user_event ON orders (event_id, user_id);
-- Timeout sweeper scans non-terminal orders by age.
CREATE INDEX orders_open_by_age ON orders (updated_at)
  WHERE state NOT IN ('CONFIRMED','CANCELLED','FAILED');

CREATE TABLE seats (
  id            BIGSERIAL PRIMARY KEY,
  event_id      BIGINT NOT NULL REFERENCES events(id),
  section       TEXT NOT NULL,
  row_label     TEXT NOT NULL,
  seat_number   INT NOT NULL,
  status        TEXT NOT NULL DEFAULT 'AVAILABLE'
                CHECK (status IN ('AVAILABLE','RESERVED','SOLD')),
  order_id      BIGINT REFERENCES orders(id),
  version       BIGINT NOT NULL DEFAULT 0,
  UNIQUE (event_id, section, row_label, seat_number),
  -- A seat is held by an order exactly when it is not available.
  CHECK ((status = 'AVAILABLE') = (order_id IS NULL))
);
CREATE INDEX seats_event_section ON seats (event_id, section);

CREATE TABLE order_items (
  order_id          BIGINT NOT NULL REFERENCES orders(id),
  seat_id           BIGINT NOT NULL REFERENCES seats(id),
  unit_price_cents  BIGINT NOT NULL CHECK (unit_price_cents >= 0),
  PRIMARY KEY (order_id, seat_id)
);
CREATE INDEX order_items_seat ON order_items (seat_id);

-- Final line of defense: one ticket per seat, ever (no resale; see ADR 0001).
CREATE TABLE tickets (
  id         BIGSERIAL PRIMARY KEY,
  seat_id    BIGINT NOT NULL UNIQUE REFERENCES seats(id),
  order_id   BIGINT NOT NULL REFERENCES orders(id),
  issued_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX tickets_order ON tickets (order_id);

CREATE TABLE idempotency_keys (
  user_id        TEXT NOT NULL,
  key            TEXT NOT NULL,
  request_hash   TEXT NOT NULL,
  response_code  INT,            -- NULL while the request is in progress (-> 409)
  response_body  JSONB,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, key)
);
CREATE INDEX idempotency_keys_created ON idempotency_keys (created_at);

CREATE TABLE outbox (
  id            BIGSERIAL PRIMARY KEY,
  aggregate_id  TEXT NOT NULL,
  topic         TEXT NOT NULL,
  payload       JSONB NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at  TIMESTAMPTZ
);
CREATE INDEX outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
