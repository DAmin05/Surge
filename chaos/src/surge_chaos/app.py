"""Chaos controller: the war room's chaos panel calls this.

GET /actions lists what can be broken; POST /actions/{id} breaks it (optionally for
`durationS` seconds, then it heals itself); GET /events is the timeline the war room
draws on its charts. Development only: it holds the Docker socket.
"""

import contextlib
import os
from collections.abc import AsyncIterator
from typing import Any

import httpx
from fastapi import FastAPI, HTTPException, Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
from pydantic import BaseModel, ConfigDict

from .actions import BY_ID, Backends, Controller


@contextlib.asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    project = os.environ.get("COMPOSE_PROJECT", "")
    if not project:
        yield  # unit tests: no Docker, no controller
        return
    from .backends import (
        DockerContainers,
        HttpOrderControl,
        HttpPaymentFaults,
        HttpToxiproxy,
        redis_nodes,
    )

    async with httpx.AsyncClient(timeout=5) as http:
        app.state.controller = Controller(
            Backends(
                containers=DockerContainers(project, redis_nodes()),
                toxiproxy=HttpToxiproxy(
                    http, os.environ.get("TOXIPROXY_URL", "http://toxiproxy:8474")
                ),
                payment=HttpPaymentFaults(
                    http, os.environ.get("PAYMENT_URL", "http://payment:8000")
                ),
                order=HttpOrderControl(http, os.environ.get("ORDER_URL", "http://order:8083")),
            )
        )
        yield
        await app.state.controller.reset()


app = FastAPI(title="surge-chaos", lifespan=lifespan)


class RunRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    durationS: float | None = None  # noqa: N815 (JSON field name)


def controller() -> Controller:
    c = getattr(app.state, "controller", None)
    if c is None:
        raise HTTPException(503, "chaos controller not configured")
    return c


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


@app.get("/metrics")
def metrics() -> Response:
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)


@app.get("/actions")
def actions() -> list[dict[str, Any]]:
    return controller().catalogue()


@app.post("/actions/{action_id}")
async def run(action_id: str, req: RunRequest | None = None) -> dict[str, Any]:
    if action_id not in BY_ID:
        raise HTTPException(404, "unknown action")
    event = await controller().run(action_id, req.durationS if req else None)
    if event.error:
        raise HTTPException(502, event.error)
    return event.as_dict()


@app.delete("/actions/{action_id}")
async def stop(action_id: str) -> dict[str, str]:
    if action_id not in BY_ID:
        raise HTTPException(404, "unknown action")
    await controller().stop(action_id)
    return {"status": "stopped"}


@app.post("/reset")
async def reset() -> dict[str, str]:
    await controller().reset()
    return {"status": "reset"}


@app.get("/events")
def events() -> list[dict[str, Any]]:
    return [e.as_dict() for e in controller().log]
