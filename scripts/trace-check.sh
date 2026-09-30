#!/usr/bin/env bash
# Week-4 exit criterion (tracing half): one trace spans every service a checkout
# touches. Buys a seat through the gateway, takes the checkout's trace id from the
# `x-trace-id` response header, waits for the order to confirm, then asks Jaeger which
# services that single trace contains.
set -euo pipefail
# A fresh UUID: uuidgen on macOS (which has no /proc), the kernel's elsewhere.
uuid() { if command -v uuidgen >/dev/null; then uuidgen | tr 'A-Z' 'a-z'; else cat /proc/sys/kernel/random/uuid; fi; }

compose=(docker compose)
gw="http://localhost:${GATEWAY_PORT:-8080}"
jaeger="http://localhost:${JAEGER_PORT:-16686}"
fail() { echo "FAIL: $*" >&2; exit 1; }
sql() { "${compose[@]}" exec -T postgres psql -qtA -U orders_owner -d "${POSTGRES_DB:-surge}" -c "$1"; }
required=(gateway order inventory payment)

event=$("${compose[@]}" exec -T postgres psql -qtA -U orders_owner -d "${POSTGRES_DB:-surge}" \
  -v sections=1 -v rows=1 -v seats_per_row=4 < infra/postgres/seed.sql)
seat=$(sql "SELECT min(id) FROM orders.seats WHERE event_id = $event")
jar=$(mktemp); trap 'rm -f "$jar"' EXIT

curl -sf -c "$jar" -X POST "$gw/api/session" > /dev/null
curl -sf -b "$jar" -X POST "$gw/api/queue/$event/join" > /dev/null
token=""
for _ in $(seq 50); do
  pos=$(curl -sf -b "$jar" "$gw/api/queue/$event/position" || true)
  if [[ "$pos" == *ADMITTED* ]]; then token=$(sed -E 's/.*"token":"([^"]+)".*/\1/' <<<"$pos"); break; fi
  sleep 0.1
done
[[ -n "$token" ]] || fail "not admitted"
hold=$(curl -sf -X POST "$gw/api/holds" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  -d "{\"eventId\":$event,\"section\":\"A\",\"seatIds\":[$seat]}" | sed -E 's/.*"holdId":"([^"]+)".*/\1/')
headers=$(mktemp)
order=$(curl -sf -D "$headers" -X POST "$gw/api/checkout" -H "Authorization: Bearer $token" \
  -H "Idempotency-Key: $(uuid)" -H 'Content-Type: application/json' \
  -d "{\"holdId\":\"$hold\"}" | sed -E 's/.*"orderId":([0-9]+).*/\1/')
trace=$(grep -i '^x-trace-id:' "$headers" | tr -d '\r' | awk '{print $2}'); rm -f "$headers"
[[ -n "$trace" ]] || fail "gateway returned no x-trace-id"
echo "order $order, trace $trace"

for _ in $(seq 60); do
  [[ $(sql "SELECT state FROM orders.orders WHERE id = $order") == CONFIRMED ]] && break
  sleep 0.5
done
[[ $(sql "SELECT state FROM orders.orders WHERE id = $order") == CONFIRMED ]] || fail "order $order not confirmed"

# Spans are exported in batches; give every service time to flush.
services=""
for _ in $(seq 30); do
  services=$(curl -sf "$jaeger/api/traces/$trace" | python3 -c '
import json, sys
d = json.load(sys.stdin)["data"]
if d:
    t = d[0]
    names = sorted({p["serviceName"] for p in t["processes"].values()})
    print(" ".join(names), len(t["spans"]))
' 2>/dev/null || true)
  missing=0
  for s in "${required[@]}"; do [[ " $services " == *" $s "* ]] || missing=1; done
  ((missing == 0)) && break
  sleep 1
done
echo "trace $trace: services = ${services% *}; spans = ${services##* }"
for s in "${required[@]}"; do
  [[ " $services " == *" $s "* ]] || fail "service '$s' missing from trace $trace"
done
echo "TRACE OK: one checkout trace spans ${required[*]} ($jaeger/trace/$trace)"
