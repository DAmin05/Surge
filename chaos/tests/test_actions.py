import asyncio
from typing import Any

from fastapi.testclient import TestClient

from surge_chaos.actions import Backends, Controller
from surge_chaos.app import app


class Fake:
    """Records every backend call."""

    def __init__(self) -> None:
        self.calls: list[tuple[Any, ...]] = []

    # containers
    async def kill(self, service: str) -> str:
        self.calls.append(("kill", service))
        return service

    async def start(self, service: str) -> None:
        self.calls.append(("start", service))

    async def restart(self, service: str) -> None:
        self.calls.append(("restart", service))

    async def redis_primary(self) -> str:
        return "redis-2"

    # toxiproxy
    async def set_enabled(self, proxy: str, enabled: bool) -> None:
        self.calls.append(("proxy", proxy, enabled))

    async def add_latency(self, proxy: str, ms: int) -> None:
        self.calls.append(("latency", proxy, ms))

    async def clear(self, proxy: str) -> None:
        self.calls.append(("clear", proxy))

    # payment
    async def set(self, faults: dict[str, Any]) -> None:
        self.calls.append(("faults", faults))

    async def reset(self) -> None:
        self.calls.append(("faults-reset",))

    # order
    async def set_relay_paused(self, paused: bool) -> None:
        self.calls.append(("relay-paused", paused))


def controller() -> tuple[Controller, Fake]:
    f = Fake()
    return Controller(Backends(containers=f, toxiproxy=f, payment=f, order=f)), f


def test_timed_action_heals_itself_and_is_logged() -> None:
    async def run() -> None:
        c, f = controller()
        event = await c.run("partition-order-postgres", duration_s=0.05)
        assert f.calls == [("proxy", "order_postgres", False)]
        assert event.ended_at is None
        assert [a["active"] for a in c.catalogue() if a["id"] == "partition-order-postgres"] == [
            True
        ]
        await asyncio.sleep(0.1)
        assert f.calls[-1] == ("proxy", "order_postgres", True)
        assert event.ended_at is not None

    asyncio.run(run())


def test_killing_a_redis_primary_brings_that_node_back() -> None:
    async def run() -> None:
        c, f = controller()
        event = await c.run("kill-redis-primary", duration_s=None)
        assert event.detail == "redis-2"
        await c.stop("kill-redis-primary")
        assert f.calls == [("kill", "redis-2"), ("start", "redis-2")]

    asyncio.run(run())


def test_reset_undoes_everything_active() -> None:
    async def run() -> None:
        c, f = controller()
        await c.run("payment-failures", duration_s=60)
        await c.run("slow-order-postgres", duration_s=60)
        await c.reset()
        assert ("faults-reset",) in f.calls
        assert ("clear", "order_postgres") in f.calls
        assert all(e.ended_at is not None for e in c.log)
        assert not any(a["active"] for a in c.catalogue())

    asyncio.run(run())


def test_actions_without_undo_end_immediately() -> None:
    async def run() -> None:
        c, f = controller()
        event = await c.run("restart-redpanda")
        assert f.calls == [("restart", "redpanda")]
        assert event.ended_at is not None

    asyncio.run(run())


def test_api_needs_a_configured_controller() -> None:
    assert TestClient(app).get("/actions").status_code == 503


def test_relay_pause_resumes_after_its_duration() -> None:
    async def run() -> None:
        c, f = controller()
        await c.run("pause-outbox-relay", duration_s=0.05)
        assert f.calls == [("relay-paused", True)]
        await asyncio.sleep(0.1)
        assert f.calls == [("relay-paused", True), ("relay-paused", False)]

    asyncio.run(run())


def test_late_successes_land_after_timeout_and_grace(monkeypatch: Any) -> None:
    monkeypatch.setenv("PAYMENT_TIMEOUT", "PT10S")
    monkeypatch.setenv("PIN_GRACE", "PT10S")

    async def run() -> None:
        c, f = controller()
        event = await c.run("payment-timeouts", duration_s=0.01)
        assert f.calls[0] == ("faults", {"timeout_rate": 0.5, "timeout_delay_s": 25.0})
        assert event.detail == "late successes after 25 s"

    asyncio.run(run())


def test_iso_durations() -> None:
    from surge_chaos.actions import seconds

    assert seconds("PT2M") == 120
    assert seconds("PT30S") == 30
    assert seconds("PT1M30S") == 90
    assert seconds("PT0.5S") == 0.5
