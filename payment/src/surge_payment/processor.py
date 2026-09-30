"""Turns order events into charges and refunds.

PAYMENT_REQUESTED: create the payment row; only the call that created it charges.
Kafka is at-least-once, so the same request can arrive many times; it's charged once.
REFUND_REQUESTED: refund a captured payment, once.
"""

import asyncio
import logging
import time
from typing import Any, Protocol

from prometheus_client import Counter

from .faults import Faults
from .store import Store

log = logging.getLogger(__name__)

CHARGES = Counter("payment_charges_total", "Charges settled", ["status"])
REQUESTS = Counter("payment_requests_total", "Payment requests seen", ["result"])
REFUNDS = Counter("payment_refunds_total", "Refund requests", ["result"])


class Webhooks(Protocol):
    async def send(self, payment_key: str, status: str) -> bool: ...


class Events(Protocol):
    async def publish(self, event: dict[str, Any]) -> None: ...


class Processor:
    def __init__(self, store: Store, faults: Faults, webhooks: Webhooks, events: Events):
        self._store = store
        self._faults = faults
        self._webhooks = webhooks
        self._events = events
        self._tasks: set[asyncio.Task[None]] = set()

    async def handle(self, event: dict[str, Any]) -> None:
        kind = event.get("type")
        if kind == "PAYMENT_REQUESTED":
            key = event["paymentKey"]
            created = await self._store.create_if_absent(
                key, int(event["orderId"]), int(event["amountCents"])
            )
            REQUESTS.labels("new" if created else "duplicate").inc()
            if created:
                self._spawn(self.charge(key))
        elif kind == "REFUND_REQUESTED":
            refunded = await self._store.refund(event["paymentKey"])
            REFUNDS.labels("refunded" if refunded else "not_captured").inc()
            if refunded:
                await self._publish("PAYMENT_REFUNDED", event["paymentKey"])

    async def resume_pending(self) -> int:
        """After a restart: finish charges that were in flight. settle() only moves a
        PENDING payment, so resuming one that did finish changes nothing."""
        keys = await self._store.pending()
        for key in keys:
            self._spawn(self.charge(key))
        return len(keys)

    async def charge(self, payment_key: str) -> None:
        plan = self._faults.plan()
        await asyncio.sleep(plan.delay_s)
        status = "CAPTURED" if plan.outcome == "SUCCEEDED" else "FAILED"
        if not await self._store.settle(payment_key, status):
            return  # someone else settled it
        CHARGES.labels(status).inc()
        await self._publish(
            "PAYMENT_SUCCEEDED" if plan.outcome == "SUCCEEDED" else "PAYMENT_FAILED", payment_key
        )
        # Duplicate deliveries race each other on purpose.
        await asyncio.gather(
            *(self._webhooks.send(payment_key, plan.outcome) for _ in range(plan.deliveries))
        )
        if plan.stale_failure_after_s is not None:
            await asyncio.sleep(plan.stale_failure_after_s)
            await self._webhooks.send(payment_key, "FAILED")

    async def _publish(self, kind: str, payment_key: str) -> None:
        order = await self._store.order_of(payment_key)
        if order is None:
            return
        try:
            await self._events.publish(
                {
                    "type": kind,
                    "paymentKey": payment_key,
                    "orderId": order[0],
                    "amountCents": order[1],
                    "occurredAtMs": int(time.time() * 1000),
                }
            )
        except Exception as e:  # observers only: never let this break a charge
            log.warning("payment event %s for %s not published: %s", kind, payment_key, e)

    def _spawn(self, coro: Any) -> None:
        task = asyncio.create_task(coro)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    async def drain(self) -> None:
        """Waits for in-flight charges (tests, shutdown)."""
        while self._tasks:
            await asyncio.gather(*list(self._tasks), return_exceptions=True)
