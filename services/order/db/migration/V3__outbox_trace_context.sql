-- W3C trace context (traceparent / tracestate) of the transaction that wrote the event.
-- The relay publishes the event inside that context, so one trace follows a checkout
-- through the outbox, Kafka and Payment, even though publishing happens later on
-- another thread.
ALTER TABLE outbox ADD COLUMN headers JSONB NOT NULL DEFAULT '{}'::jsonb;
