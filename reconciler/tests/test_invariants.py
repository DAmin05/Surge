"""Every invariant against a real Postgres, migrated with the repo's own SQL and queried
as reconciler_ro: clean data reports zero, and each planted violation is caught."""

import asyncio
import re
import time
import uuid
from collections.abc import Iterator
from pathlib import Path

import psycopg
import pytest
from testcontainers.community.postgres import PostgresContainer

from surge_reconciler.config import Settings
from surge_reconciler.invariants import SQL_CHECKS, no_zombie_holds
from surge_reconciler.runner import Reconciler

ROOT = Path(__file__).resolve().parents[2]
PASSWORDS = {
    "orders_owner_password": "oo",
    "orders_app_password": "oa",
    "payments_owner_password": "po",
    "payments_app_password": "pa",
    "reconciler_password": "rr",
}


def _migrate(dsn: str, files: list[Path], placeholders: dict[str, str]) -> None:
    with psycopg.connect(dsn, autocommit=True) as conn:
        for f in files:
            sql = re.sub(r"\$\{([^}]+)\}", lambda m: placeholders[m.group(1)], f.read_text())
            conn.execute(sql)


@pytest.fixture(scope="module")
def pg() -> Iterator[dict[str, str]]:
    with PostgresContainer(
        "postgres:16-alpine", username="admin", password="admin", dbname="surge"
    ) as c:
        host, port = c.get_container_host_ip(), c.get_exposed_port(5432)

        def dsn(user: str, pw: str) -> str:
            return f"postgresql://{user}:{pw}@{host}:{port}/surge"

        _migrate(
            dsn("admin", "admin"),
            sorted((ROOT / "infra/postgres/bootstrap").glob("V*.sql")),
            {**PASSWORDS, "flyway:database": "surge"},
        )
        _migrate(
            dsn("orders_owner", "oo"),
            sorted((ROOT / "services/order/db/migration").glob("V*.sql")),
            {},
        )
        _migrate(
            dsn("payments_owner", "po"), sorted((ROOT / "payment/db/migration").glob("V*.sql")), {}
        )
        yield {
            "orders": dsn("orders_owner", "oo"),
            "payments": dsn("payments_owner", "po"),
            "reconciler": dsn("reconciler_ro", "rr"),
        }


def settings(dsn: str) -> Settings:
    return Settings(
        db_dsn=dsn,
        redis_nodes=[],
        payment_timeout_s=120,
        order_sweep_interval_s=5,
        hold_sweep_interval_s=1,
        check_interval_s=0,
        refund_grace_s=60,
        slack_s=15,
    )


def violations(pg: dict[str, str]) -> dict[str, int]:
    report = asyncio.run(Reconciler(settings(pg["reconciler"]), None).run())
    return {name: r.violations for name, r in report.invariants.items() if r.error is None}


def sale(
    pg: dict[str, str],
    seats: int = 2,
    state: str = "CONFIRMED",
    payment: str | None = "CAPTURED",
    tickets: int | None = None,
    user: str = "u",
    capacity: int = 100,
) -> dict[str, object]:
    """One order, consistent unless told otherwise."""
    key = uuid.uuid4()
    with psycopg.connect(pg["orders"], autocommit=True) as o:
        event = o.execute(
            "INSERT INTO events (name, starts_at, capacity) VALUES ('t', now(), %s) RETURNING id",
            (capacity,),
        ).fetchone()[0]
        order = o.execute(
            "INSERT INTO orders (user_id, event_id, section, state, amount_cents, payment_key)"
            " VALUES (%s, %s, 'A', %s, %s, %s) RETURNING id",
            (user, event, state, 1000 * seats, key),
        ).fetchone()[0]
        seat_status = "SOLD" if state == "CONFIRMED" else "RESERVED"
        seat_ids = []
        for n in range(seats):
            seat_ids.append(
                o.execute(
                    "INSERT INTO seats (event_id, section, row_label, seat_number, status,"
                    " order_id) VALUES (%s, 'A', 'R', %s, %s, %s) RETURNING id",
                    (event, n, seat_status, order),
                ).fetchone()[0]
            )
            o.execute("INSERT INTO order_items VALUES (%s, %s, 1000)", (order, seat_ids[-1]))
        for seat in seat_ids[
            : (seats if tickets is None else tickets) if state == "CONFIRMED" else 0
        ]:
            o.execute("INSERT INTO tickets (seat_id, order_id) VALUES (%s, %s)", (seat, order))
    if payment:
        with psycopg.connect(pg["payments"], autocommit=True) as p:
            p.execute(
                "INSERT INTO payments (payment_key, order_id, amount_cents, status)"
                " VALUES (%s, %s, %s, %s)",
                (key, order, 1000 * seats, payment),
            )
    return {"event": event, "order": order, "key": key, "seats": seat_ids}


def age(pg: dict[str, str], db: str, sql: str, *params: object) -> None:
    with psycopg.connect(pg[db], autocommit=True) as c:
        c.execute(sql, params)


def test_a_consistent_sale_has_zero_violations(pg: dict[str, str]) -> None:
    sale(pg)
    sale(pg, state="PAYMENT_PENDING", payment="PENDING")
    sale(pg, state="CANCELLED", payment="FAILED")
    assert set(violations(pg).values()) == {0}
    assert set(violations(pg)) == set(SQL_CHECKS)


def test_each_planted_violation_is_caught(pg: dict[str, str]) -> None:
    before = violations(pg)

    # 1: more tickets than capacity.
    sale(pg, seats=3, capacity=2)
    # 2: a sold seat with no ticket (tickets=1 of 2) is also invariant 3's "partial ticketing".
    partial = sale(pg, seats=2, tickets=1)
    # 3: captured payment, order cancelled, no refund long after the grace period.
    unrefunded = sale(pg, state="CANCELLED", payment="CAPTURED")
    age(
        pg,
        "payments",
        "UPDATE payments SET updated_at = now() - interval '10 minutes' WHERE payment_key = %s",
        unrefunded["key"],
    )
    # 4: an order stuck in PAYMENT_PENDING well past T.
    stuck = sale(pg, state="PAYMENT_PENDING", payment="PENDING")
    age(
        pg,
        "orders",
        "UPDATE orders SET updated_at = now() - interval '1 hour' WHERE id = %s",
        stuck["order"],
    )
    # 6: five live seats for one user in one event.
    greedy = sale(pg, seats=5, user="greedy")
    # 7: an order whose total disagrees with its items.
    age(pg, "orders", "UPDATE orders SET amount_cents = 1 WHERE id = %s", greedy["order"])

    after = violations(pg)
    grew = {name for name in after if after[name] > before[name]}
    assert grew == {
        "sold_within_capacity",
        "no_seat_sold_twice",
        "payments_settled",
        "no_stuck_orders",
        "user_seat_limit",
        "order_amounts",
    }
    assert partial  # used above


def test_reconciler_cannot_write(pg: dict[str, str]) -> None:
    with (
        psycopg.connect(pg["reconciler"]) as c,
        pytest.raises(psycopg.errors.ReadOnlySqlTransaction),
    ):
        c.execute("DELETE FROM orders.tickets")


class FakeRedis:
    def __init__(self, sections: dict[str, list[tuple[str, float]]]):
        self._sections = sections

    async def smembers(self, key: str) -> set[bytes]:
        return {s.encode() for s in self._sections}

    async def zrangebyscore(
        self, key: str, lo: str, hi: float, withscores: bool
    ) -> list[tuple[bytes, float]]:
        entry = key[len("{evt:") : key.index("}")].replace(":sec:", ":")
        return [(h.encode(), sc) for h, sc in self._sections[entry] if sc <= hi]


def test_holds_past_their_lease_are_zombies() -> None:
    now_ms = time.time() * 1000
    redis = FakeRedis(
        {
            "1:A": [
                ("fresh", now_ms + 60_000),
                ("just-lapsed", now_ms - 500),
                ("zombie", now_ms - 60_000),
            ]
        }
    )
    rows = asyncio.run(no_zombie_holds(redis, settings("unused")))
    assert [r["hold_id"] for r in rows] == ["zombie"]
