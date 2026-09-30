#!/usr/bin/env bash
# Week-3 exit criterion: 10k WebSocket clients receive seat updates in under 200 ms p99.
#
# Seeds a fresh event, then runs the wsbench container (gateway image) inside the
# Compose network against the running stack. The gateway must allow the bench's
# connect rate from one IP: start the stack with WS_CONNECT_PER_IP_PER_SEC=100000.
set -euo pipefail

compose=(docker compose)
event=$("${compose[@]}" exec -T postgres psql -qtA -U orders_owner -d "${POSTGRES_DB:-surge}" \
  -v sections=3 -v rows=2 -v seats_per_row=50 < infra/postgres/seed.sql)
echo "event $event: 300 seats"

"${compose[@]}" --profile bench run --rm --no-deps \
  -e EVENT_ID="$event" \
  -e CLIENTS="${BENCH_CLIENTS:-10000}" \
  -e HOLDS="${BENCH_HOLDS:-100}" \
  -e HOLD_RATE="${BENCH_HOLD_RATE:-20}" \
  -e P99_BUDGET_MS="${BENCH_P99_BUDGET_MS:-200}" \
  wsbench
