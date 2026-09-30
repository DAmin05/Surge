"""Kafka plumbing: order-events in, payment-events out (observers only)."""

import asyncio
import json
import logging
from typing import Any

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer

from .processor import Processor

log = logging.getLogger(__name__)

ORDER_EVENTS = "order-events"
PAYMENT_EVENTS = "payment-events"


class KafkaEvents:
    def __init__(self, bootstrap: str):
        self._producer = AIOKafkaProducer(
            bootstrap_servers=bootstrap, acks="all", enable_idempotence=True
        )

    async def start(self) -> None:
        await self._producer.start()

    async def stop(self) -> None:
        await self._producer.stop()

    async def publish(self, event: dict[str, Any]) -> None:
        await self._producer.send_and_wait(
            PAYMENT_EVENTS, json.dumps(event).encode(), key=event["paymentKey"].encode()
        )


async def consume_orders(bootstrap: str, processor: Processor, stop: asyncio.Event) -> None:
    """At-least-once: offsets are committed after the batch is handled. Handling is
    idempotent (the payment row is the dedup key)."""
    consumer = AIOKafkaConsumer(
        ORDER_EVENTS,
        bootstrap_servers=bootstrap,
        group_id="payment",
        enable_auto_commit=False,
        auto_offset_reset="earliest",
    )
    while not stop.is_set():
        try:
            await consumer.start()
            break
        except Exception as e:
            log.warning("kafka not ready: %s", e)
            await asyncio.sleep(1)
    try:
        while not stop.is_set():
            batch = await consumer.getmany(timeout_ms=500, max_records=200)
            for records in batch.values():
                for record in records:
                    await _handle_with_retry(processor, record.value, stop)
            if batch:
                await consumer.commit()
    finally:
        await consumer.stop()


async def _handle_with_retry(processor: Processor, raw: bytes, stop: asyncio.Event) -> None:
    backoff = 0.1
    while not stop.is_set():
        try:
            await processor.handle(json.loads(raw))
            return
        except Exception as e:
            log.warning("order event failed, retrying: %s", e)
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 5.0)
