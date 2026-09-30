#!/bin/bash
# One-shot: create Surge topics if missing. Safe to re-run.
# Topic contracts are in docs/design.md ("Kafka topics").
set -euo pipefail
B="-X brokers=${BROKERS:-redpanda:9092}"
P="${PARTITIONS:-12}"

create() {
  local name="$1"; shift
  if rpk topic describe "$name" $B >/dev/null 2>&1; then
    echo "exists: $name"
  else
    rpk topic create "$name" $B "$@"
  fi
}

create seat-events       -p "$P" -r 1
create seat-status       -p "$P" -r 1 -c cleanup.policy=compact
create order-events      -p "$P" -r 1
create payment-events    -p "$P" -r 1
# Every gateway instance reads all revocations, so one partition is enough.
create token-revocations -p 1    -r 1 -c retention.ms=900000
rpk topic list $B
