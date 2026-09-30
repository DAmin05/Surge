-- Seed one on-sale event: <sections> sections named A, B, C..., each with
-- <rows> rows of <seats_per_row> seats. Section prices step up by 5.00 from
-- <base_price> cents. Prints the new event id.
--
--   psql -U orders_owner -d surge -v sections=10 -v rows=20 -v seats_per_row=50 -f seed.sql
--
-- Defaults: 10 x 20 x 50 = 10,000 seats.
\set ON_ERROR_STOP on
\if :{?sections} \else \set sections 10 \endif
\if :{?rows} \else \set rows 20 \endif
\if :{?seats_per_row} \else \set seats_per_row 50 \endif
\if :{?base_price} \else \set base_price 5000 \endif
\if :{?name} \else \set name 'Surge launch night' \endif

SELECT CASE WHEN :sections BETWEEN 1 AND 26 THEN 1 ELSE 1 / 0 END AS sections_must_be_1_to_26 \gset

WITH e AS (
  INSERT INTO orders.events (name, starts_at, capacity)
  VALUES (:'name', now() + interval '1 day', :sections * :rows * :seats_per_row)
  RETURNING id
), prices AS (
  INSERT INTO orders.section_prices (event_id, section, price_cents)
  SELECT e.id, chr(64 + s), :base_price + (s - 1) * 500
    FROM e, generate_series(1, :sections) s
), seats AS (
  INSERT INTO orders.seats (event_id, section, row_label, seat_number)
  SELECT e.id, chr(64 + s), 'R' || lpad(r::text, 2, '0'), n
    FROM e, generate_series(1, :sections) s, generate_series(1, :rows) r, generate_series(1, :seats_per_row) n
)
SELECT id AS event_id FROM e;
