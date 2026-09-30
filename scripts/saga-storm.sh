#!/usr/bin/env bash
# Week-2 exit criterion, second half: with Payment injecting failures, timeouts,
# duplicate and out-of-order callbacks, every order still ends in a terminal state and
# the Reconciler finds zero invariant violations.
#
# Needs a running stack with a short payment timeout, e.g.
#   PAYMENT_TIMEOUT=PT10S PIN_GRACE=PT10S docker compose up -d
set -euo pipefail

compose=(docker compose)
fail() { echo "FAIL: $*" >&2; exit 1; }
# Observation spans both schemas, so it runs as the admin (no service role can).
sql() { "${compose[@]}" exec -T postgres psql -qtA -U "${POSTGRES_ADMIN_USER:-surge_admin}" -d "${POSTGRES_DB:-surge}" -c "$1"; }

timeout_s=$(sed -E 's/^PT([0-9]+)S$/\1/' <<<"${PAYMENT_TIMEOUT:-PT10S}")
[[ "$timeout_s" =~ ^[0-9]+$ ]] || fail "set PAYMENT_TIMEOUT as PT<n>S (got ${PAYMENT_TIMEOUT:-})"

event=$("${compose[@]}" exec -T postgres psql -qtA -U orders_owner -d "${POSTGRES_DB:-surge}" \
  -v sections=3 -v rows=2 -v seats_per_row=10 < infra/postgres/seed.sql)
seats=$(sql "SELECT string_agg(section || ':' || ids, ';') FROM (
  SELECT section, string_agg(id::text, ',' ORDER BY id) ids FROM orders.seats
   WHERE event_id = $event GROUP BY section) s")
echo "event $event: 3 sections x 20 seats"

# Timeouts stall past T, so those charges succeed after the order was cancelled: refunds.
faults=$(printf '{"failure_rate":0.3,"timeout_rate":0.15,"timeout_delay_s":%d,"duplicate_rate":0.3,"out_of_order_rate":0.25,"latency_ms_min":20,"latency_ms_max":400}' $((timeout_s * 2 + 5)))
echo "faults: $faults"
summary=$("${compose[@]}" exec -T -e EVENT_ID="$event" -e SEATS="$seats" -e FAULTS="$faults" \
  -e BUYERS="${BUYERS:-80}" -e SEED="${SEED:-$RANDOM}" payment python - < scripts/saga_storm.py)
echo "buyers: $summary"
[[ "$summary" != *IDEMPOTENCY_BROKEN* ]] || fail "a retried checkout created two orders"

# Wait for the saga to settle: no open orders, no pending charges, late successes refunded.
deadline=$((SECONDS + timeout_s * 3 + 120))
while :; do
  open=$(sql "SELECT count(*) FROM orders.orders WHERE event_id = $event
               AND state NOT IN ('CONFIRMED', 'CANCELLED', 'FAILED')")
  pending=$(sql "SELECT count(*) FROM payments.payments p JOIN orders.orders o USING (payment_key)
                  WHERE o.event_id = $event AND p.status = 'PENDING'")
  unrefunded=$(sql "SELECT count(*) FROM payments.payments p JOIN orders.orders o USING (payment_key)
                     WHERE o.event_id = $event AND p.status = 'CAPTURED' AND o.state = 'CANCELLED'")
  [[ "$open" == 0 && "$pending" == 0 && "$unrefunded" == 0 ]] && break
  ((SECONDS < deadline)) || fail "not settled: open=$open pending=$pending unrefunded=$unrefunded"
  sleep 2
done

echo "orders by state:"
sql "SELECT '  ' || state || ': ' || count(*) FROM orders.orders WHERE event_id = $event GROUP BY state ORDER BY state"
echo "payments by status:"
sql "SELECT '  ' || p.status || ': ' || count(*) FROM payments.payments p JOIN orders.orders o USING (payment_key)
      WHERE o.event_id = $event GROUP BY p.status ORDER BY p.status"
confirmed=$(sql "SELECT count(*) FROM orders.orders WHERE event_id = $event AND state = 'CONFIRMED'")
cancelled=$(sql "SELECT count(*) FROM orders.orders WHERE event_id = $event AND state = 'CANCELLED'")
refunded=$(sql "SELECT count(*) FROM payments.payments p JOIN orders.orders o USING (payment_key)
                 WHERE o.event_id = $event AND p.status = 'REFUNDED'")
((confirmed > 0 && cancelled > 0)) || fail "faults didn't bite: confirmed=$confirmed cancelled=$cancelled"

# Let Inventory apply the terminal order events, then audit.
sleep 3
audit=$("${compose[@]}" exec -T inventory curl -sf -X POST http://reconciler:8001/audit)
total=$(grep -o '"total_violations":[0-9]*' <<<"$audit" | cut -d: -f2)
errors=$(grep -o '"errors":[0-9]*' <<<"$audit" | cut -d: -f2)
[[ "$total" == 0 && "$errors" == 0 ]] || fail "reconciler: violations=$total errors=$errors: $audit"

"${compose[@]}" exec -T inventory curl -sf -X DELETE http://payment:8000/faults > /dev/null
echo "SAGA STORM OK: every order terminal ($confirmed confirmed, $cancelled cancelled, $refunded refunded late successes), 0 invariant violations"
