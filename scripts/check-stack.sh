#!/usr/bin/env bash
# Week-0 exit criterion: `docker compose up` brings everything healthy.
#
# Passes when every long-running service reports healthy (each one defines a
# healthcheck) and every one-shot job has exited 0. Fails fast on a failed job or an
# unhealthy service, and after TIMEOUT seconds otherwise.
#
#   scripts/check-stack.sh [extra docker compose args...]
set -euo pipefail

TIMEOUT="${TIMEOUT:-300}"
ONE_SHOTS=" keygen redis-cluster-init redpanda-topics db-bootstrap db-migrate-orders db-migrate-payments "
compose=(docker compose "$@")

deadline=$((SECONDS + TIMEOUT))
while :; do
  pending=()
  failed=()
  expected="$("${compose[@]}" config --services | sort)"
  status="$("${compose[@]}" ps -a --format '{{.Service}} {{.State}} {{.Health}} {{.ExitCode}}' | sort)"

  for svc in $expected; do
    line="$(grep "^$svc " <<<"$status" || true)"
    read -r _ state health code <<<"${line:-$svc missing - -}"
    if [[ "$ONE_SHOTS" == *" $svc "* ]]; then
      case "$state" in
        exited) [[ "$health" == "0" || "$code" == "0" ]] || failed+=("$svc exited ${code:-$health}") ;;
        *) pending+=("$svc ($state)") ;;
      esac
    else
      case "$state/$health" in
        running/healthy) ;;
        running/unhealthy) failed+=("$svc unhealthy") ;;
        running/) failed+=("$svc has no healthcheck") ;;
        exited/*|dead/*|restarting/*) failed+=("$svc $state") ;;
        *) pending+=("$svc ($state${health:+/$health})") ;;
      esac
    fi
  done

  if ((${#failed[@]})); then
    printf 'FAIL: %s\n' "${failed[@]}" >&2
    "${compose[@]}" ps -a >&2
    exit 1
  fi
  if ((${#pending[@]} == 0)); then
    echo "OK: all $(wc -w <<<"$expected") services healthy or completed"
    exit 0
  fi
  if ((SECONDS >= deadline)); then
    printf 'TIMEOUT waiting for: %s\n' "${pending[@]}" >&2
    "${compose[@]}" ps -a >&2
    exit 1
  fi
  sleep 3
done
