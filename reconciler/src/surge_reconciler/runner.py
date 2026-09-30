"""Runs every invariant and keeps the latest report."""

import logging
import time
from dataclasses import asdict, dataclass, field
from typing import Any

from prometheus_client import Counter, Gauge
from psycopg import AsyncConnection

from .config import Settings
from .invariants import INVARIANTS, SQL_CHECKS, Row, no_zombie_holds

log = logging.getLogger(__name__)

VIOLATIONS = Gauge(
    "reconciler_invariant_violations", "Rows violating each invariant, last run", ["invariant"]
)
TOTAL = Gauge("reconciler_violations_total", "Violations across all invariants, last run")
LAST_RUN = Gauge("reconciler_last_run_timestamp_seconds", "When the last run finished")
ERRORS = Counter("reconciler_check_errors_total", "Checks that could not run", ["invariant"])

SAMPLES = 10


@dataclass
class Result:
    number: int
    description: str
    violations: int = 0
    samples: list[Row] = field(default_factory=list)
    error: str | None = None


@dataclass
class Report:
    finished_at: float
    duration_ms: int
    total_violations: int
    errors: int
    invariants: dict[str, Result]

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)


class Reconciler:
    def __init__(self, settings: Settings, redis: Any | None):
        self._settings = settings
        self._redis = redis
        self.latest: Report | None = None

    async def run(self) -> Report:
        started = time.monotonic()
        results = {inv.name: Result(inv.number, inv.description) for inv in INVARIANTS}
        try:
            async with await AsyncConnection.connect(self._settings.db_dsn) as conn:
                for name, check in SQL_CHECKS.items():
                    await self._check(results[name], name, lambda c=check: c(conn, self._settings))
                    await conn.rollback()
        except Exception as e:
            for name in SQL_CHECKS:
                if results[name].error is None and not results[name].samples:
                    results[name].error = f"database unavailable: {e}"
                    ERRORS.labels(name).inc()
        if self._redis is None:
            results["no_zombie_holds"].error = "redis not configured"
        else:
            await self._check(
                results["no_zombie_holds"],
                "no_zombie_holds",
                lambda: no_zombie_holds(self._redis, self._settings),
            )

        total = sum(r.violations for r in results.values())
        errors = sum(1 for r in results.values() if r.error)
        for name, r in results.items():
            VIOLATIONS.labels(name).set(r.violations)
        TOTAL.set(total)
        LAST_RUN.set(time.time())
        if total:
            log.error("INVARIANT VIOLATIONS: %s", {n: r.violations for n, r in results.items()})
        self.latest = Report(
            time.time(), int((time.monotonic() - started) * 1000), total, errors, results
        )
        return self.latest

    async def _check(self, result: Result, name: str, call: Any) -> None:
        try:
            rows = await call()
            result.violations = len(rows)
            result.samples = [_jsonable(r) for r in rows[:SAMPLES]]
        except Exception as e:
            result.error = str(e)
            ERRORS.labels(name).inc()
            log.warning("check %s failed: %s", name, e)


def _jsonable(row: Row) -> Row:
    return {
        k: (v if isinstance(v, str | int | float | bool | None) else str(v)) for k, v in row.items()
    }
