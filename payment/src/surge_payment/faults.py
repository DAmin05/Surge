"""Runtime-configurable failure injection: the reason this mock exists.

Real payment gateways fail, stall, and call back twice or out of order. The saga has
to survive all of it, so every one of those behaviours can be switched on here, live,
from the chaos panel (PUT /faults).
"""

import random
import threading
from dataclasses import asdict, dataclass, replace
from typing import Literal

Outcome = Literal["SUCCEEDED", "FAILED"]


@dataclass(frozen=True)
class FaultConfig:
    failure_rate: float = 0.0
    """Share of charges that are declined."""
    latency_ms_min: int = 20
    latency_ms_max: int = 200
    """Normal processing time, uniform in [min, max]."""
    timeout_rate: float = 0.0
    """Share of charges that stall for timeout_delay_s, then succeed (late success)."""
    timeout_delay_s: float = 180.0
    duplicate_rate: float = 0.0
    """Share of results delivered 2-3 times, concurrently."""
    out_of_order_rate: float = 0.0
    """Share of successes followed later by a stale FAILED callback."""

    def validate(self) -> "FaultConfig":
        for name in ("failure_rate", "timeout_rate", "duplicate_rate", "out_of_order_rate"):
            value = getattr(self, name)
            if not 0.0 <= value <= 1.0:
                raise ValueError(f"{name} must be within [0, 1]")
        if not 0 <= self.latency_ms_min <= self.latency_ms_max:
            raise ValueError("need 0 <= latency_ms_min <= latency_ms_max")
        if self.timeout_delay_s < 0:
            raise ValueError("timeout_delay_s must be >= 0")
        return self


@dataclass(frozen=True)
class ChargePlan:
    outcome: Outcome
    delay_s: float
    deliveries: int
    """How many times the webhook is sent (1 = normal)."""
    stale_failure_after_s: float | None
    """If set, a contradictory FAILED is sent this long after the real result."""


class Faults:
    """Thread-safe holder for the current config; decides each charge's fate."""

    def __init__(self, config: FaultConfig | None = None, rng: random.Random | None = None):
        self._lock = threading.Lock()
        self._config = (config or FaultConfig()).validate()
        self._rng = rng or random.Random()

    @property
    def config(self) -> FaultConfig:
        with self._lock:
            return self._config

    def update(self, **changes: object) -> FaultConfig:
        with self._lock:
            self._config = replace(self._config, **changes).validate()
            return self._config

    def reset(self) -> FaultConfig:
        with self._lock:
            self._config = FaultConfig()
            return self._config

    def plan(self) -> ChargePlan:
        with self._lock:
            c, r = self._config, self._rng
            if r.random() < c.timeout_rate:
                outcome: Outcome = "SUCCEEDED"
                delay = c.timeout_delay_s
            else:
                outcome = "FAILED" if r.random() < c.failure_rate else "SUCCEEDED"
                delay = r.uniform(c.latency_ms_min, c.latency_ms_max) / 1000
            deliveries = r.choice((2, 3)) if r.random() < c.duplicate_rate else 1
            stale = None
            if outcome == "SUCCEEDED" and r.random() < c.out_of_order_rate:
                stale = r.uniform(0.05, 0.5)
            return ChargePlan(outcome, delay, deliveries, stale)

    def as_dict(self) -> dict[str, object]:
        return asdict(self.config)
