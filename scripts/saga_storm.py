"""Buyers against the running stack while Payment misbehaves on purpose.

Runs inside the payment container (it has httpx and can reach the internal services):

    docker compose exec -T -e EVENT_ID=.. -e SEATS=A:1,2;B:3,4 payment python - < scripts/saga_storm.py

Every buyer holds 1-2 seats and checks out, retrying the checkout with the same
Idempotency-Key to prove retries don't create orders. Prints a JSON summary; the
invariants are judged afterwards by the Reconciler (see saga-storm.sh).
"""

import asyncio
import json
import os
import random
import uuid
from collections import Counter

import httpx

INVENTORY = "http://inventory:8082"
ORDER = "http://order:8083"
PAYMENT = "http://localhost:8000"

EVENT_ID = int(os.environ["EVENT_ID"])
SEATS = {s.split(":")[0]: [int(x) for x in s.split(":")[1].split(",")] for s in os.environ["SEATS"].split(";")}
BUYERS = int(os.environ.get("BUYERS", "80"))
RETRIES = int(os.environ.get("CHECKOUT_RETRIES", "3"))
FAULTS = json.loads(os.environ.get("FAULTS", "{}"))


async def buyer(client: httpx.AsyncClient, n: int, rng: random.Random, stats: Counter[str]) -> None:
    user = f"storm-{n}"
    # Skewed demand: half the buyers want the first section.
    section = next(iter(SEATS)) if rng.random() < 0.5 else rng.choice(list(SEATS))
    want = rng.sample(SEATS[section], k=min(rng.choice((1, 2)), len(SEATS[section])))
    res = await client.post(f"{INVENTORY}/holds", headers={"X-User-Id": user},
                            json={"eventId": EVENT_ID, "section": section, "seatIds": want})
    stats[f"hold_{res.status_code}"] += 1
    if res.status_code != 201:
        return
    hold = res.json()["holdId"]
    key = str(uuid.uuid4())
    codes = await asyncio.gather(*(
        client.post(f"{ORDER}/checkout", headers={"X-User-Id": user, "Idempotency-Key": key},
                    json={"holdId": hold})
        for _ in range(RETRIES)
    ))
    for r in codes:
        stats[f"checkout_{r.status_code}"] += 1
    orders = {r.json()["orderId"] for r in codes if r.status_code == 201}
    if len(orders) > 1:
        stats["IDEMPOTENCY_BROKEN"] += 1
    if orders:
        stats["orders"] += 1


async def main() -> None:
    rng = random.Random(int(os.environ.get("SEED", "42")))
    stats: Counter[str] = Counter()
    async with httpx.AsyncClient(timeout=30) as client:
        (await client.put(f"{PAYMENT}/faults", json=FAULTS)).raise_for_status()
        await asyncio.gather(*(buyer(client, n, rng, stats) for n in range(BUYERS)))
    print(json.dumps(dict(sorted(stats.items()))))


asyncio.run(main())
