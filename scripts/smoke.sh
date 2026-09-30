#!/usr/bin/env bash
# End-to-end smoke test against a running stack (`make up` first). Exercises the
# real Redis Cluster, gRPC, Postgres (through Toxiproxy) and Redpanda:
#
#   1. seed a small event
#   2. 50 concurrent buyers race for one seat on the cluster -> exactly one hold
#   3. a multi-seat hold, then a conflicting hold on one of its seats -> 409
#   4. checkout -> 201, and a retry with the same Idempotency-Key replays it
#   5. the saga completes: relay -> Payment -> signed webhook -> CONFIRMED, tickets issued
#   6. seat events (held, extended, sold) are on the seat-events topic, in seq order
set -euo pipefail

compose=(docker compose)
fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "ok   $*"; }
sql() { "${compose[@]}" exec -T postgres psql -qtA -U orders_owner -d "${POSTGRES_DB:-surge}" -c "$1"; }
# Internal services aren't published; call them from inside the network.
call() { "${compose[@]}" exec -T inventory curl -s -o /dev/stderr -w '%{http_code}' "$@" 2>/tmp/smoke-body || true; }
body() { cat /tmp/smoke-body; }

event=$("${compose[@]}" exec -T postgres psql -qtA -U orders_owner -d "${POSTGRES_DB:-surge}" \
  -v sections=2 -v rows=1 -v seats_per_row=6 < infra/postgres/seed.sql)
[[ "$event" =~ ^[0-9]+$ ]] || fail "seed returned '$event'"
mapfile -t A < <(sql "SELECT id FROM orders.seats WHERE event_id = $event AND section = 'A' ORDER BY id")
mapfile -t B < <(sql "SELECT id FROM orders.seats WHERE event_id = $event AND section = 'B' ORDER BY id")
pass "seeded event $event (A: ${A[*]}; B: ${B[*]})"

# --- 2. race on the real cluster ------------------------------------------------
race=$("${compose[@]}" exec -T inventory sh -c "
  seq 50 | xargs -P 50 -I{} curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8082/holds \
    -H 'X-User-Id: racer-{}' -H 'Content-Type: application/json' \
    -d '{\"eventId\":$event,\"section\":\"B\",\"seatIds\":[${B[0]}]}'" | sort | uniq -c | tr -s ' ')
[[ "$race" == *" 1 201"* && "$race" == *" 49 409"* ]] || fail "race results: $race"
pass "50 concurrent buyers, one seat: exactly one hold ($(echo $race | tr '\n' ' '))"

# --- 3. hold + conflict ---------------------------------------------------------
code=$(call -X POST http://localhost:8082/holds -H 'X-User-Id: alice' -H 'Content-Type: application/json' \
  -d "{\"eventId\":$event,\"section\":\"A\",\"seatIds\":[${A[0]},${A[1]}]}")
[[ "$code" == 201 ]] || fail "alice hold: $code $(body)"
hold=$(body | sed -E 's/.*"holdId":"([^"]+)".*/\1/')
pass "alice holds A:${A[0]},${A[1]} ($hold)"

code=$(call -X POST http://localhost:8082/holds -H 'X-User-Id: bob' -H 'Content-Type: application/json' \
  -d "{\"eventId\":$event,\"section\":\"A\",\"seatIds\":[${A[1]},${A[2]}]}")
[[ "$code" == 409 ]] && body | grep -q SEAT_TAKEN || fail "bob conflicting hold: $code $(body)"
pass "bob's overlapping hold rejected, all-or-nothing"

# --- 4. checkout ----------------------------------------------------------------
code=$(call -X POST http://order:8083/checkout -H 'X-User-Id: mallory' -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" -d "{\"holdId\":\"$hold\"}")
[[ "$code" == 403 ]] || fail "checkout of someone else's hold: $code $(body)"
pass "checkout of someone else's hold rejected"

key=$(cat /proc/sys/kernel/random/uuid)
code=$(call -X POST http://order:8083/checkout -H 'X-User-Id: alice' -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $key" -d "{\"holdId\":\"$hold\"}")
[[ "$code" == 201 ]] || fail "checkout: $code $(body)"
order=$(body | sed -E 's/.*"orderId":([0-9]+).*/\1/')
claimed=$(sql "SELECT count(*) FROM orders.seats WHERE order_id = $order AND status IN ('RESERVED', 'SOLD')")
outbox=$(sql "SELECT count(*) FROM orders.outbox WHERE aggregate_id = '$order' AND payload->>'type' = 'PAYMENT_REQUESTED'")
[[ "$claimed" == 2 && "$outbox" == 1 ]] || fail "order $order: claimed=$claimed payment requests=$outbox"
pass "checkout created order $order: 2 seats claimed, PAYMENT_REQUESTED in the outbox"

code=$(call -X POST http://order:8083/checkout -H 'X-User-Id: alice' -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $key" -d "{\"holdId\":\"$hold\"}")
[[ "$code" == 201 ]] && body | grep -qE "\"orderId\": ?$order[,}]" || fail "checkout retry: $code $(body)"
[[ $(sql "SELECT count(*) FROM orders.orders WHERE user_id = 'alice' AND event_id = $event") == 1 ]] \
  || fail "retry created a second order"
pass "retrying the checkout with the same Idempotency-Key replays order $order"


# --- 5. saga: relay -> payment -> webhook -> confirmed ----------------------------
for _ in $(seq 60); do
  state=$(sql "SELECT state FROM orders.orders WHERE id = $order")
  [[ "$state" == CONFIRMED ]] && break
  sleep 0.5
done
[[ "$state" == CONFIRMED ]] || fail "order $order stuck in $state"
tickets=$(sql "SELECT count(*) FROM orders.tickets WHERE order_id = $order")
[[ "$tickets" == 2 ]] || fail "order $order has $tickets tickets"
pass "payment captured, webhook confirmed order $order: 2 tickets issued"

# --- 6. seat events -------------------------------------------------------------
events=$("${compose[@]}" exec -T redpanda timeout 20 rpk topic consume seat-events -o :end -f '%v\n' 2>/dev/null \
  | grep "\"eventId\":$event," | grep '"section":"A"' || true)
types=$(grep -o '"type":"[A-Z_]*"' <<<"$events" | cut -d'"' -f4 | tr '\n' ' ')
seqs=$(grep -o '"seq":[0-9]*' <<<"$events" | cut -d: -f2 | sort -n | tr '\n' ' ')
for _ in $(seq 20); do
  [[ "$types" == *SEAT_SOLD* ]] && break
  sleep 0.5
  events=$("${compose[@]}" exec -T redpanda timeout 20 rpk topic consume seat-events -o :end -f '%v\n' 2>/dev/null \
    | grep "\"eventId\":$event," | grep '"section":"A"' || true)
  types=$(grep -o '"type":"[A-Z_]*"' <<<"$events" | cut -d'"' -f4 | tr '\n' ' ')
done
seqs=$(grep -o '"seq":[0-9]*' <<<"$events" | cut -d: -f2 | sort -n | tr '\n' ' ')
[[ "$types" == *SEAT_HELD* && "$types" == *SEAT_HOLD_EXTENDED* && "$types" == *SEAT_SOLD* ]] \
  || fail "seat events for section A: '$types'"
[[ "$seqs" == 1\ 2\ 3\ 4\ * ]] || fail "section A seqs: '$seqs'"
pass "seat-events has section A's changes in seq order ($types)"

echo "SMOKE OK"
