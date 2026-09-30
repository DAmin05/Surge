# loadtest

[k6](https://k6.io) buyers, run from the `k6` Compose service (profile `load`) so
they reach the gateway on the internal network.

- `buyer.js`: one iteration is one anonymous buyer. Session → waiting room → hold 1-4
  seats in one section → check out with an `Idempotency-Key` (retried on 5xx, network
  errors and `REQUEST_IN_PROGRESS`) → wait for the order to settle. `RELEASE` of them
  release the hold instead, `WALK_AWAY` let it expire. `SKEW` of them want the first
  section (the hot shard).

```bash
make seed                                  # prints the event id
EVENT_ID=1 RATE=30 DURATION=2m make load   # buyers per second, for how long
```

The run fails only on correctness: a retried checkout that yields a second order
(`idempotency_broken`) or a status no client should ever see (`unexpected_status`).
Chaos is allowed to fail requests; the Reconciler judges what happened to the data.
The gateway's per-IP limit applies to k6 as one IP: start the stack with
`RATE_LIMIT_IP_PER_SEC=100000` for load runs.
