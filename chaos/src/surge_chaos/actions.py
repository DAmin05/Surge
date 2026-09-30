"""The chaos catalogue: named faults the panel can inject, each with an undo.

An action runs, stays active for `duration_s` (if it has one), then reverts itself.
Everything that happens is logged with timestamps so the war room can mark it on its
charts. Backends are small protocols so the logic is testable without Docker.
"""

import asyncio
import time
from collections.abc import Awaitable, Callable
from dataclasses import asdict, dataclass, field
from typing import Any, Protocol

from prometheus_client import Counter, Gauge

ACTIONS = Counter("chaos_actions_total", "Chaos actions started", ["action"])
ACTIVE = Gauge("chaos_active", "1 while a chaos action is in effect", ["action"])


class Containers(Protocol):
    async def kill(self, service: str) -> str: ...
    async def start(self, service: str) -> None: ...
    async def restart(self, service: str) -> None: ...
    async def redis_primary(self) -> str: ...


class Toxiproxy(Protocol):
    async def set_enabled(self, proxy: str, enabled: bool) -> None: ...
    async def add_latency(self, proxy: str, ms: int) -> None: ...
    async def clear(self, proxy: str) -> None: ...


class PaymentFaults(Protocol):
    async def set(self, faults: dict[str, Any]) -> None: ...
    async def reset(self) -> None: ...


@dataclass
class Backends:
    containers: Containers
    toxiproxy: Toxiproxy
    payment: PaymentFaults


Step = Callable[[Backends], Awaitable[str | None]]


@dataclass(frozen=True)
class Action:
    id: str
    name: str
    description: str
    start: Step
    undo: Step | None
    default_duration_s: float | None


@dataclass
class Event:
    action: str
    name: str
    started_at: float
    detail: str | None = None
    ended_at: float | None = None
    error: str | None = None

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)


def _kill(service: str) -> Step:
    async def run(b: Backends) -> str | None:
        return await b.containers.kill(service)

    return run


def _start(service: str) -> Step:
    async def run(b: Backends) -> str | None:
        await b.containers.start(service)
        return None

    return run


def _payment(faults: dict[str, Any]) -> Step:
    async def run(b: Backends) -> str | None:
        await b.payment.set(faults)
        return None

    return run


async def _payment_reset(b: Backends) -> str | None:
    await b.payment.reset()
    return None


def _proxy(proxy: str, enabled: bool) -> Step:
    async def run(b: Backends) -> str | None:
        await b.toxiproxy.set_enabled(proxy, enabled)
        return None

    return run


def _latency(proxy: str, ms: int) -> Step:
    async def run(b: Backends) -> str | None:
        await b.toxiproxy.add_latency(proxy, ms)
        return None

    return run


def _clear(proxy: str) -> Step:
    async def run(b: Backends) -> str | None:
        await b.toxiproxy.clear(proxy)
        return None

    return run


def _restart(service: str) -> Step:
    async def run(b: Backends) -> str | None:
        await b.containers.restart(service)
        return None

    return run


# The Redis node that was killed is remembered so undo can bring it back.
_killed_redis: dict[str, str] = {}


async def _kill_redis_primary(b: Backends) -> str | None:
    node = await b.containers.redis_primary()
    await b.containers.kill(node)
    _killed_redis["node"] = node
    return node


async def _start_killed_redis(b: Backends) -> str | None:
    node = _killed_redis.pop("node", None)
    if node:
        await b.containers.start(node)
    return node


CATALOGUE = [
    Action(
        "kill-redis-primary",
        "Kill Redis primary",
        "Kills the primary for some hash slots mid-sale. Its replica takes over and may"
        " have lost recent holds; Postgres still decides every seat.",
        _kill_redis_primary,
        _start_killed_redis,
        30,
    ),
    Action(
        "kill-inventory",
        "Kill inventory",
        "Kills the Inventory service; it restarts after the duration.",
        _kill("inventory"),
        _start("inventory"),
        15,
    ),
    Action(
        "payment-failures",
        "Payment 40% failure",
        "Payment declines 40% of charges.",
        _payment({"failure_rate": 0.4}),
        _payment_reset,
        60,
    ),
    Action(
        "payment-timeouts",
        "Payment timeout storm",
        "Half the charges stall past the payment timeout, then succeed: late successes"
        " get refunded.",
        _payment({"timeout_rate": 0.5}),
        _payment_reset,
        60,
    ),
    Action(
        "duplicate-callbacks",
        "Duplicate callbacks",
        "Every result is delivered 2-3 times, plus stale out-of-order failures.",
        _payment({"duplicate_rate": 1.0, "out_of_order_rate": 0.5}),
        _payment_reset,
        60,
    ),
    Action(
        "partition-order-postgres",
        "Partition Order↔Postgres",
        "Cuts Order off from Postgres (Toxiproxy). Checkouts fail fast; nothing is lost.",
        _proxy("order_postgres", False),
        _proxy("order_postgres", True),
        20,
    ),
    Action(
        "slow-order-postgres",
        "Slow Order↔Postgres",
        "Adds 300 ms latency between Order and Postgres.",
        _latency("order_postgres", 300),
        _clear("order_postgres"),
        30,
    ),
    Action(
        "restart-redpanda",
        "Restart Redpanda",
        "Restarts the broker during the sale; the outbox relay and consumers resume.",
        _restart("redpanda"),
        None,
        None,
    ),
]
BY_ID = {a.id: a for a in CATALOGUE}


@dataclass
class Controller:
    backends: Backends
    log: list[Event] = field(default_factory=list)
    _undo_tasks: dict[str, asyncio.Task[None]] = field(default_factory=dict)

    def catalogue(self) -> list[dict[str, Any]]:
        return [
            {
                "id": a.id,
                "name": a.name,
                "description": a.description,
                "defaultDurationS": a.default_duration_s,
                "active": a.id in self._undo_tasks,
            }
            for a in CATALOGUE
        ]

    async def run(self, action_id: str, duration_s: float | None = None) -> Event:
        action = BY_ID[action_id]
        event = Event(action.id, action.name, time.time())
        self.log.append(event)
        del self.log[:-200]
        ACTIONS.labels(action.id).inc()
        try:
            event.detail = await action.start(self.backends)
        except Exception as e:
            event.error = str(e)
            event.ended_at = time.time()
            return event
        if action.undo is None:
            event.ended_at = time.time()
            return event
        ACTIVE.labels(action.id).set(1)
        duration = duration_s if duration_s is not None else action.default_duration_s
        if duration is not None:
            self._cancel(action.id)
            self._undo_tasks[action.id] = asyncio.create_task(
                self._undo_later(action, event, duration)
            )
        return event

    async def _undo_later(self, action: Action, event: Event, duration: float) -> None:
        await asyncio.sleep(duration)
        await self._undo(action, event)
        self._undo_tasks.pop(action.id, None)

    async def _undo(self, action: Action, event: Event | None) -> None:
        try:
            if action.undo is not None:
                await action.undo(self.backends)
        finally:
            ACTIVE.labels(action.id).set(0)
            if event is not None and event.ended_at is None:
                event.ended_at = time.time()

    def _cancel(self, action_id: str) -> None:
        task = self._undo_tasks.pop(action_id, None)
        if task:
            task.cancel()

    async def stop(self, action_id: str) -> None:
        """Ends an action now instead of at the end of its duration."""
        action = BY_ID[action_id]
        self._cancel(action_id)
        open_event = next(
            (e for e in reversed(self.log) if e.action == action_id and e.ended_at is None), None
        )
        await self._undo(action, open_event)

    async def reset(self) -> None:
        """Undoes everything that's still active."""
        for action_id in list(self._undo_tasks):
            await self.stop(action_id)
        for e in self.log:
            if e.ended_at is None:
                await self._undo(BY_ID[e.action], e)
