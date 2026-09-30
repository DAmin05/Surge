"""Mock payment gateway with fault injection.

Consumes PAYMENT_REQUESTED / REFUND_REQUESTED from order-events, charges each payment
key at most once, and reports results to Order by signed webhook (authoritative) and
to payment-events (observers only). Faults are configurable at runtime: PUT /faults.
"""

import asyncio
import contextlib
import logging
from collections.abc import AsyncIterator

import httpx
from fastapi import FastAPI, HTTPException, Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
from psycopg_pool import AsyncConnectionPool
from pydantic import BaseModel, ConfigDict

from .config import Settings
from .faults import Faults
from .kafka import KafkaEvents, consume_orders
from .processor import Processor
from .store import PgStore
from .webhook import WebhookSender

log = logging.getLogger(__name__)
faults = Faults()


@contextlib.asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings = Settings.from_env()
    if not settings.kafka_bootstrap:
        # No broker (unit tests): serve the HTTP API only.
        yield
        return
    pool = AsyncConnectionPool(settings.db_dsn, min_size=2, max_size=10, open=False)
    await pool.open(wait=True)
    http = httpx.AsyncClient()
    events = KafkaEvents(settings.kafka_bootstrap)
    await events.start()
    store = PgStore(pool)
    processor = Processor(
        store,
        faults,
        WebhookSender(
            http,
            settings.order_webhook_url,
            settings.webhook_secret,
            give_up_after_s=settings.webhook_give_up_after_s,
        ),
        events,
    )
    app.state.store = store
    resumed = await processor.resume_pending()
    if resumed:
        log.info("resumed %d pending charges", resumed)
    stop = asyncio.Event()
    consumer = asyncio.create_task(consume_orders(settings.kafka_bootstrap, processor, stop))
    try:
        yield
    finally:
        stop.set()
        await consumer
        await events.stop()
        await http.aclose()
        await pool.close()


app = FastAPI(title="surge-payment", lifespan=lifespan)


class FaultUpdate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    failure_rate: float | None = None
    latency_ms_min: int | None = None
    latency_ms_max: int | None = None
    timeout_rate: float | None = None
    timeout_delay_s: float | None = None
    duplicate_rate: float | None = None
    out_of_order_rate: float | None = None


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


@app.get("/metrics")
def metrics() -> Response:
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)


@app.get("/payments/{payment_key}")
async def payment_status(payment_key: str) -> dict[str, str]:
    """Order's timeout sweeper asks here before cancelling anything."""
    store = getattr(app.state, "store", None)
    if store is None:
        raise HTTPException(503, "no database configured")
    status = await store.status(payment_key)
    if status is None:
        raise HTTPException(404, "unknown payment")
    return {"paymentKey": payment_key, "status": status}


@app.get("/faults")
def get_faults() -> dict[str, object]:
    return faults.as_dict()


@app.put("/faults")
def put_faults(update: FaultUpdate) -> dict[str, object]:
    try:
        faults.update(**update.model_dump(exclude_none=True))
    except ValueError as e:
        raise HTTPException(422, str(e)) from e
    return faults.as_dict()


@app.delete("/faults")
def reset_faults() -> dict[str, object]:
    faults.reset()
    return faults.as_dict()
