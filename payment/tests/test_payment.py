import asyncio
import hashlib
import hmac
import json
import random

import httpx
import pytest

from surge_payment.faults import FaultConfig, Faults
from surge_payment.processor import Processor
from surge_payment.webhook import WebhookSender, body, sign


class MemoryStore:
    """payments table semantics: conditional status changes, one row per key."""

    def __init__(self) -> None:
        self.rows: dict[str, dict[str, object]] = {}

    async def create_if_absent(self, payment_key: str, order_id: int, amount_cents: int) -> bool:
        if payment_key in self.rows:
            return False
        self.rows[payment_key] = {"order": order_id, "amount": amount_cents, "status": "PENDING"}
        return True

    async def settle(self, payment_key: str, status: str) -> bool:
        row = self.rows.get(payment_key)
        if row is None or row["status"] != "PENDING":
            return False
        row["status"] = status
        return True

    async def refund(self, payment_key: str) -> bool:
        row = self.rows.get(payment_key)
        if row is None or row["status"] != "CAPTURED":
            return False
        row["status"] = "REFUNDED"
        return True

    async def status(self, payment_key: str) -> str | None:
        row = self.rows.get(payment_key)
        return None if row is None else str(row["status"])

    async def pending(self) -> list[str]:
        return [k for k, r in self.rows.items() if r["status"] == "PENDING"]

    async def order_of(self, payment_key: str) -> tuple[int, int] | None:
        row = self.rows.get(payment_key)
        return None if row is None else (int(row["order"]), int(row["amount"]))  # type: ignore[arg-type]


class RecordingWebhooks:
    def __init__(self) -> None:
        self.sent: list[tuple[str, str]] = []

    async def send(self, payment_key: str, status: str) -> bool:
        self.sent.append((payment_key, status))
        return True


class RecordingEvents:
    def __init__(self) -> None:
        self.events: list[dict[str, object]] = []

    async def publish(self, event: dict[str, object]) -> None:
        self.events.append(event)


def processor(
    config: FaultConfig,
) -> tuple[Processor, MemoryStore, RecordingWebhooks, RecordingEvents]:
    store, hooks, events = MemoryStore(), RecordingWebhooks(), RecordingEvents()
    fast = FaultConfig(**{**config.__dict__, "latency_ms_min": 0, "latency_ms_max": 1})
    return Processor(store, Faults(fast, random.Random(7)), hooks, events), store, hooks, events


def request(key: str, order: int = 1, amount: int = 5000) -> dict[str, object]:
    return {"type": "PAYMENT_REQUESTED", "paymentKey": key, "orderId": order, "amountCents": amount}


def test_signature_matches_what_order_verifies() -> None:
    payload = body("7c9e6679-7425-40de-944b-e07fc1f90ae7", "SUCCEEDED", 1)
    assert payload == (
        b'{"paymentKey":"7c9e6679-7425-40de-944b-e07fc1f90ae7","status":"SUCCEEDED","occurredAtMs":1}'
    )
    expected = hmac.new(b"secret", payload, hashlib.sha256).hexdigest()
    assert sign(payload, "secret") == "sha256=" + expected


def test_redelivered_requests_charge_once() -> None:
    async def run() -> None:
        p, store, hooks, events = processor(FaultConfig())
        await asyncio.gather(*(p.handle(request("k1")) for _ in range(20)))
        await p.drain()
        assert store.rows["k1"]["status"] == "CAPTURED"
        assert hooks.sent == [("k1", "SUCCEEDED")]
        assert [e["type"] for e in events.events] == ["PAYMENT_SUCCEEDED"]

    asyncio.run(run())


def test_declines_report_failure() -> None:
    async def run() -> None:
        p, store, hooks, _ = processor(FaultConfig(failure_rate=1.0))
        await p.handle(request("k2"))
        await p.drain()
        assert store.rows["k2"]["status"] == "FAILED"
        assert hooks.sent == [("k2", "FAILED")]

    asyncio.run(run())


def test_duplicate_and_out_of_order_callbacks_are_injected() -> None:
    async def run() -> None:
        p, _, hooks, _ = processor(FaultConfig(duplicate_rate=1.0, out_of_order_rate=1.0))
        await p.handle(request("k3"))
        await p.drain()
        statuses = [s for _, s in hooks.sent]
        # The real result 2-3 times, then a stale contradictory FAILED.
        assert statuses[:-1] == ["SUCCEEDED"] * (len(statuses) - 1)
        assert 2 <= len(statuses) - 1 <= 3
        assert statuses[-1] == "FAILED"

    asyncio.run(run())


def test_refunds_only_captured_payments_once() -> None:
    async def run() -> None:
        p, store, _, events = processor(FaultConfig())
        await p.handle(request("k4"))
        await p.drain()
        refund = {"type": "REFUND_REQUESTED", "paymentKey": "k4", "orderId": 1, "amountCents": 5000}
        await p.handle(refund)
        await p.handle(refund)
        assert store.rows["k4"]["status"] == "REFUNDED"
        assert [e["type"] for e in events.events] == ["PAYMENT_SUCCEEDED", "PAYMENT_REFUNDED"]

        await p.handle({**refund, "paymentKey": "never-charged"})
        assert "never-charged" not in store.rows

    asyncio.run(run())


def test_pending_charges_resume_after_restart() -> None:
    async def run() -> None:
        p, store, hooks, _ = processor(FaultConfig())
        await store.create_if_absent("k5", 5, 100)  # created, then the process died
        assert await p.resume_pending() == 1
        await p.drain()
        assert store.rows["k5"]["status"] == "CAPTURED"
        assert hooks.sent == [("k5", "SUCCEEDED")]

    asyncio.run(run())


def test_fault_config_is_validated() -> None:
    faults = Faults()
    with pytest.raises(ValueError):
        faults.update(failure_rate=1.5)
    with pytest.raises(ValueError):
        faults.update(latency_ms_min=10, latency_ms_max=5)
    assert faults.update(timeout_rate=0.2).timeout_rate == 0.2


def test_timeouts_become_late_successes() -> None:
    plan = Faults(FaultConfig(timeout_rate=1.0, timeout_delay_s=150), random.Random(1)).plan()
    assert plan.outcome == "SUCCEEDED"
    assert plan.delay_s == 150


def test_webhook_retries_until_acknowledged_and_treats_404_as_final() -> None:
    calls: list[httpx.Request] = []
    answers = iter([503, 500, 200])

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(next(answers))

    async def run() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            sender = WebhookSender(client, "http://order/hook", "s", first_backoff_s=0.01)
            assert await sender.send("k6", "SUCCEEDED") is True
        assert len(calls) == 3
        sent = calls[-1]
        assert sent.headers["Surge-Signature"] == sign(sent.content, "s")
        assert json.loads(sent.content)["status"] == "SUCCEEDED"

        async with httpx.AsyncClient(
            transport=httpx.MockTransport(lambda r: httpx.Response(404))
        ) as client:
            assert await WebhookSender(client, "http://o", "s").send("k7", "FAILED") is False

    asyncio.run(run())


def test_webhook_gives_up_eventually() -> None:
    async def run() -> None:
        async with httpx.AsyncClient(
            transport=httpx.MockTransport(lambda r: httpx.Response(503))
        ) as client:
            sender = WebhookSender(
                client, "http://o", "s", give_up_after_s=0.1, first_backoff_s=0.02
            )
            assert await sender.send("k8", "SUCCEEDED") is False

    asyncio.run(run())


def test_webhook_carries_the_trace_context() -> None:
    from opentelemetry.sdk.trace import TracerProvider

    tracer = TracerProvider().get_tracer("test")
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200)

    async def run() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            with tracer.start_as_current_span("charge") as span:
                await WebhookSender(client, "http://o", "s").send("k9", "SUCCEEDED")
                trace_id = format(span.get_span_context().trace_id, "032x")
        assert trace_id in seen[0].headers["traceparent"]

    asyncio.run(run())
