"""Reconciler: proves the sale kept its promises.

Runs invariants 1-7 every CHECK_INTERVAL during a sale; POST /audit runs them once on
demand (the full post-sale audit). Results: GET /violations and Prometheus gauges
(reconciler_invariant_violations{invariant}), which the war room shows as a big 0.
"""

import asyncio
import contextlib
import logging
from collections.abc import AsyncIterator
from typing import Any

from fastapi import FastAPI, HTTPException, Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

from .config import Settings
from .runner import Reconciler

log = logging.getLogger(__name__)


def _redis(nodes: list[str]) -> Any | None:
    if not nodes:
        return None
    host, port = nodes[0].rsplit(":", 1)
    if len(nodes) == 1:
        from redis.asyncio import Redis

        return Redis(host=host, port=int(port))
    from redis.asyncio.cluster import RedisCluster

    return RedisCluster(host=host, port=int(port))


@contextlib.asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings = Settings.from_env()
    reconciler = Reconciler(settings, _redis(settings.redis_nodes))
    app.state.reconciler = reconciler
    stop = asyncio.Event()

    async def loop() -> None:
        while not stop.is_set():
            try:
                await reconciler.run()
            except Exception:
                log.exception("reconciler run failed")
            with contextlib.suppress(TimeoutError):
                await asyncio.wait_for(stop.wait(), settings.check_interval_s)

    task = asyncio.create_task(loop()) if settings.check_interval_s > 0 else None
    try:
        yield
    finally:
        stop.set()
        if task:
            await task


app = FastAPI(title="surge-reconciler", lifespan=lifespan)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


@app.get("/metrics")
def metrics() -> Response:
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)


@app.get("/violations")
def violations() -> dict[str, Any]:
    reconciler = getattr(app.state, "reconciler", None)
    if reconciler is None or reconciler.latest is None:
        raise HTTPException(503, "no run yet")
    return reconciler.latest.as_dict()


@app.post("/audit")
async def audit() -> dict[str, Any]:
    reconciler = getattr(app.state, "reconciler", None)
    if reconciler is None:
        raise HTTPException(503, "not started")
    return (await reconciler.run()).as_dict()
