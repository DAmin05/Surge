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
#   7. the buyer path through the gateway: session, waiting room, admission token,
#      hold and checkout, with tokens bound to the event and identity from the token
set -euo pipefail

compose=(docker compose)
fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "ok   $*"; }
sql() { "${compose[@]}" exec -T postgres psql -qtA -U orders_owner -d "${POSTGRES_DB:-surge}" -c "$1"; }
# Internal services aren't published; call them from inside the network.
call() { "${compose[@]}" exec -T inventory curl -s -o /dev/stderr -w '%{http_code}' "$@" 2>/tmp/smoke-body || true; }
body() { cat /tmp/smoke-body; }
# A fresh UUID: uuidgen on macOS (which has no /proc), the kernel's elsewhere.
uuid() { if command -v uuidgen >/dev/null; then uuidgen | tr 'A-Z' 'a-z'; else cat /proc/sys/kernel/random/uuid; fi; }

event=$("${compose[@]}" exec -T postgres psql -qtA -U orders_owner -d "${POSTGRES_DB:-surge}" \
  -v sections=2 -v rows=1 -v seats_per_row=6 < infra/postgres/seed.sql)
[[ "$event" =~ ^[0-9]+$ ]] || fail "seed returned '$event'"
# Word-split into arrays rather than `mapfile`, which macOS's bash 3.2 doesn't have.
A=($(sql "SELECT id FROM orders.seats WHERE event_id = $event AND section = 'A' ORDER BY id"))
B=($(sql "SELECT id FROM orders.seats WHERE event_id = $event AND section = 'B' ORDER BY id"))
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
  -H "Idempotency-Key: $(uuid)" -d "{\"holdId\":\"$hold\"}")
[[ "$code" == 403 ]] || fail "checkout of someone else's hold: $code $(body)"
pass "checkout of someone else's hold rejected"

key=$(uuid)
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

# --- 7. the buyer path through the gateway ------------------------------------
gw="http://localhost:${GATEWAY_PORT:-8080}"
jar=$(mktemp)
code=$(curl -s -o /dev/null -w '%{http_code}' -c "$jar" -X POST "$gw/api/session")
[[ "$code" == 201 ]] || fail "session: $code"
grep -q surge_session "$jar" || fail "no session cookie"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$gw/api/queue/$event/join")
[[ "$code" == 401 ]] || fail "queue without a session: $code"
curl -sf -b "$jar" -X POST "$gw/api/queue/$event/join" > /dev/null || fail "join queue"
token=""
for _ in $(seq 50); do
  pos=$(curl -sf -b "$jar" "$gw/api/queue/$event/position" || true)
  if [[ "$pos" == *ADMITTED* ]]; then token=$(sed -E 's/.*"token":"([^"]+)".*/\1/' <<<"$pos"); break; fi
  sleep 0.1
done
[[ -n "$token" ]] || fail "not admitted: $pos"
pass "session cookie, waiting room, admitted with a token for event $event"

hold_body="{\"eventId\":$event,\"section\":\"A\",\"seatIds\":[${A[4]}]}"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$gw/api/holds" -H 'Content-Type: application/json' -d "$hold_body")
[[ "$code" == 401 ]] || fail "hold without a token: $code"
other="{\"eventId\":$((event + 1000000)),\"section\":\"A\",\"seatIds\":[${A[4]}]}"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$gw/api/holds" -H "Authorization: Bearer $token" \
  -H 'Content-Type: application/json' -d "$other")
[[ "$code" == 403 ]] || fail "hold for another event: $code"
res=$(curl -s -w '\n%{http_code}' -X POST "$gw/api/holds" -H "Authorization: Bearer $token" \
  -H 'X-User-Id: mallory' -H 'Content-Type: application/json' -d "$hold_body")
[[ "$(tail -1 <<<"$res")" == 201 ]] || fail "hold via gateway: $res"
ghold=$(head -1 <<<"$res" | sed -E 's/.*"holdId":"([^"]+)".*/\1/')
res=$(curl -s -w '\n%{http_code}' -X POST "$gw/api/checkout" -H "Authorization: Bearer $token" \
  -H "Idempotency-Key: $(uuid)" -H 'Content-Type: application/json' \
  -d "{\"holdId\":\"$ghold\"}")
[[ "$(tail -1 <<<"$res")" == 201 ]] || fail "checkout via gateway: $res"
gorder=$(head -1 <<<"$res" | sed -E 's/.*"orderId":([0-9]+).*/\1/')
owner=$(sql "SELECT user_id FROM orders.orders WHERE id = $gorder")
[[ "$owner" != mallory && -n "$owner" ]] || fail "order $gorder belongs to '$owner'"
for _ in $(seq 60); do
  [[ $(sql "SELECT state FROM orders.orders WHERE id = $gorder") == CONFIRMED ]] && break
  sleep 0.5
done
[[ $(sql "SELECT state FROM orders.orders WHERE id = $gorder") == CONFIRMED ]] || fail "order $gorder not confirmed"
rm -f "$jar"
pass "hold + checkout through the gateway: order $gorder CONFIRMED for the token's user, spoofed X-User-Id ignored"

echo "SMOKE OK"
