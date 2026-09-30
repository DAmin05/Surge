"""Signed, retried callbacks to Order. The webhook is what drives the saga."""

import asyncio
import hashlib
import hmac
import json
import logging
import time

import httpx
from opentelemetry import propagate
from prometheus_client import Counter

log = logging.getLogger(__name__)

WEBHOOKS = Counter("payment_webhooks_total", "Webhook deliveries", ["status", "result"])


def body(payment_key: str, status: str, occurred_at_ms: int) -> bytes:
    """Exactly the bytes that are signed and sent (schemas/payment-webhook.schema.json)."""
    return json.dumps(
        {"paymentKey": payment_key, "status": status, "occurredAtMs": occurred_at_ms},
        separators=(",", ":"),
    ).encode()


def sign(payload: bytes, secret: str) -> str:
    """Surge-Signature header: sha256=<hex HMAC-SHA256 of the raw body>."""
    return "sha256=" + hmac.new(secret.encode(), payload, hashlib.sha256).hexdigest()


class WebhookSender:
    def __init__(
        self,
        client: httpx.AsyncClient,
        url: str,
        secret: str,
        give_up_after_s: float = 60.0,
        first_backoff_s: float = 0.2,
    ):
        self._client = client
        self._url = url
        self._secret = secret
        self._give_up_after_s = give_up_after_s
        self._first_backoff_s = first_backoff_s

    async def send(self, payment_key: str, status: str) -> bool:
        """Delivers until Order answers 2xx or 404 (both final), or gives up.

        Returns True if Order acknowledged it. Giving up is safe: Order's timeout
        sweeper asks GET /payments/{key} before cancelling anything.
        """
        payload = body(payment_key, status, int(time.time() * 1000))
        headers = {
            "Content-Type": "application/json",
            "Surge-Signature": sign(payload, self._secret),
        }
        # traceparent: Order's webhook handler joins the same trace.
        propagate.inject(headers)
        deadline = time.monotonic() + self._give_up_after_s
        backoff = self._first_backoff_s
        while True:
            try:
                res = await self._client.post(
                    self._url, content=payload, headers=headers, timeout=5
                )
                if res.is_success or res.status_code == 404:
                    WEBHOOKS.labels(status, "delivered" if res.is_success else "unknown").inc()
                    return res.is_success
                log.warning("webhook %s %s -> HTTP %s", payment_key, status, res.status_code)
            except httpx.HTTPError as e:
                log.warning("webhook %s %s failed: %s", payment_key, status, e)
            if time.monotonic() + backoff > deadline:
                WEBHOOKS.labels(status, "gave_up").inc()
                return False
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 5.0)
