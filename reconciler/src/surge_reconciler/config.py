"""Settings, from the environment only. Durations are ISO-8601 like the Java services."""

import os
import re
from dataclasses import dataclass

_ISO = re.compile(r"^PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?$")


def seconds(iso: str) -> float:
    """PT2M -> 120.0. Supports the hours/minutes/seconds subset used in this repo."""
    m = _ISO.match(iso.strip().upper())
    if not m or not any(m.groups()):
        raise ValueError(f"not an ISO-8601 duration: {iso!r}")
    h, mi, s = (float(g) if g else 0.0 for g in m.groups())
    return h * 3600 + mi * 60 + s


@dataclass(frozen=True)
class Settings:
    db_dsn: str
    redis_nodes: list[str]
    payment_timeout_s: float
    order_sweep_interval_s: float
    hold_sweep_interval_s: float
    check_interval_s: float
    refund_grace_s: float
    """How long a captured payment on a cancelled order may wait for its refund."""
    slack_s: float
    """Allowance for scheduling jitter on top of every deadline."""
    max_seats_per_user: int = 4

    @property
    def stuck_after_s(self) -> float:
        """Invariant 4: T, plus one timeout-sweeper pass, plus slack."""
        return self.payment_timeout_s + self.order_sweep_interval_s + self.slack_s

    @staticmethod
    def from_env() -> "Settings":
        env = os.environ.get
        return Settings(
            db_dsn=env("DB_DSN", "postgresql://reconciler_ro:reconciler_dev@localhost:5432/surge"),
            redis_nodes=[n for n in env("REDIS_NODES", "").split(",") if n],
            payment_timeout_s=seconds(env("PAYMENT_TIMEOUT", "PT2M")),
            order_sweep_interval_s=seconds(env("TIMEOUT_SWEEP_INTERVAL", "PT5S")),
            hold_sweep_interval_s=seconds(env("SWEEP_INTERVAL", "PT1S")),
            check_interval_s=seconds(env("CHECK_INTERVAL", "PT5S")),
            refund_grace_s=seconds(env("REFUND_GRACE", "PT60S")),
            slack_s=seconds(env("RECONCILER_SLACK", "PT15S")),
        )
