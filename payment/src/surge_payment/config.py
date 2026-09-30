"""Settings, from the environment only."""

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    db_dsn: str
    kafka_bootstrap: str
    order_webhook_url: str
    webhook_secret: str
    # Give up on a webhook after this long; Order's timeout sweeper asks us directly.
    webhook_give_up_after_s: float = 60.0

    @staticmethod
    def from_env() -> "Settings":
        return Settings(
            db_dsn=os.environ.get(
                "DB_DSN", "postgresql://payments_app:payments_app_dev@localhost:5432/surge"
            ),
            kafka_bootstrap=os.environ.get("KAFKA_BOOTSTRAP", ""),
            order_webhook_url=os.environ.get(
                "ORDER_WEBHOOK_URL", "http://localhost:8083/payments/webhook"
            ),
            webhook_secret=os.environ.get("PAYMENT_WEBHOOK_SECRET", "dev-webhook-secret"),
        )
