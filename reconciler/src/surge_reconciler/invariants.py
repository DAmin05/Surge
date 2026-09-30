"""The invariants Surge must hold at every moment of a sale (docs/design.md).

Each check returns the rows that violate it; an empty list means it holds. The target
is zero on every run. Postgres checks run as reconciler_ro in read-only transactions.
"""

import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any

from psycopg import AsyncConnection
from psycopg.rows import dict_row

from .config import Settings

Row = dict[str, Any]


@dataclass(frozen=True)
class Invariant:
    number: int
    name: str
    description: str


INVARIANTS = [
    Invariant(1, "sold_within_capacity", "Sold seats <= capacity, per event"),
    Invariant(
        2, "no_seat_sold_twice", "Each sold seat has exactly one ticket, from a confirmed order"
    ),
    Invariant(3, "payments_settled", "Every captured payment has all its tickets, or a refund"),
    Invariant(4, "no_stuck_orders", "No order in a non-terminal state past T"),
    Invariant(5, "no_zombie_holds", "No Redis hold outlives its lease plus the sweeper interval"),
    Invariant(6, "user_seat_limit", "No user has more than 4 live seats per event"),
    Invariant(7, "order_amounts", "Order totals equal the sum of their items"),
]


async def _rows(conn: AsyncConnection[Any], sql: str, *params: object) -> list[Row]:
    async with conn.cursor(row_factory=dict_row) as cur:
        await cur.execute(sql, params)
        return [dict(r) for r in await cur.fetchall()]


async def sold_within_capacity(conn: AsyncConnection[Any], s: Settings) -> list[Row]:
    return await _rows(
        conn,
        """
        SELECT e.id AS event_id, e.capacity, count(t.id) AS sold
          FROM orders.events e
          JOIN orders.seats s ON s.event_id = e.id
          JOIN orders.tickets t ON t.seat_id = s.id
         GROUP BY e.id, e.capacity
        HAVING count(t.id) > e.capacity
        """,
    )


async def no_seat_sold_twice(conn: AsyncConnection[Any], s: Settings) -> list[Row]:
    # tickets.seat_id is UNIQUE, so the first query can't match. It's verified anyway:
    # constraints prevent the bug, the audit proves it.
    return await _rows(
        conn,
        """
        SELECT 'two tickets' AS problem, seat_id, NULL::bigint AS order_id
          FROM orders.tickets GROUP BY seat_id HAVING count(*) > 1
        UNION ALL
        SELECT 'sold seat without its ticket', s.id, s.order_id
          FROM orders.seats s LEFT JOIN orders.tickets t ON t.seat_id = s.id
         WHERE s.status = 'SOLD' AND (t.id IS NULL OR t.order_id IS DISTINCT FROM s.order_id)
        UNION ALL
        SELECT 'ticket for an unconfirmed order', t.seat_id, t.order_id
          FROM orders.tickets t JOIN orders.orders o ON o.id = t.order_id
         WHERE o.state <> 'CONFIRMED'
        UNION ALL
        SELECT 'ticket for a seat not marked sold', t.seat_id, t.order_id
          FROM orders.tickets t JOIN orders.seats s ON s.id = t.seat_id
         WHERE s.status <> 'SOLD'
        """,
    )


async def payments_settled(conn: AsyncConnection[Any], s: Settings) -> list[Row]:
    return await _rows(
        conn,
        """
        WITH tickets_per_order AS (
          SELECT o.id,
                 (SELECT count(*) FROM orders.tickets t WHERE t.order_id = o.id) AS tickets,
                 (SELECT count(*) FROM orders.order_items i WHERE i.order_id = o.id) AS items
            FROM orders.orders o
        )
        SELECT 'captured without all tickets' AS problem, p.payment_key::text, o.id AS order_id,
               o.state, tpo.tickets, tpo.items
          FROM payments.payments p
          LEFT JOIN orders.orders o ON o.payment_key = p.payment_key
          LEFT JOIN tickets_per_order tpo ON tpo.id = o.id
         WHERE p.status = 'CAPTURED'
           AND p.updated_at < now() - make_interval(secs => %s)
           -- Still pending: the webhook may be lost; the timeout sweeper resolves it
           -- (and invariant 4 reports it if it doesn't).
           AND o.state IS DISTINCT FROM 'PAYMENT_PENDING'
           AND NOT (o.state = 'CONFIRMED' AND tpo.tickets = tpo.items)
        UNION ALL
        SELECT 'confirmed without a captured payment', o.payment_key::text, o.id, o.state,
               tpo.tickets, tpo.items
          FROM orders.orders o
          JOIN tickets_per_order tpo ON tpo.id = o.id
          LEFT JOIN payments.payments p ON p.payment_key = o.payment_key
         WHERE o.state = 'CONFIRMED'
           AND (p.status IS DISTINCT FROM 'CAPTURED' OR tpo.tickets <> tpo.items)
        """,
        s.refund_grace_s,
    )


async def no_stuck_orders(conn: AsyncConnection[Any], s: Settings) -> list[Row]:
    return await _rows(
        conn,
        """
        SELECT id AS order_id, state, round(extract(epoch FROM now() - updated_at)) AS age_s
          FROM orders.orders
         WHERE state NOT IN ('CONFIRMED', 'CANCELLED', 'FAILED')
           AND updated_at < now() - make_interval(secs => %s)
        """,
        s.stuck_after_s,
    )


async def user_seat_limit(conn: AsyncConnection[Any], s: Settings) -> list[Row]:
    return await _rows(
        conn,
        """
        SELECT o.event_id, o.user_id, count(*) AS seats
          FROM orders.order_items i JOIN orders.orders o ON o.id = i.order_id
         WHERE o.state NOT IN ('CANCELLED', 'FAILED')
         GROUP BY o.event_id, o.user_id
        HAVING count(*) > %s
        """,
        s.max_seats_per_user,
    )


async def order_amounts(conn: AsyncConnection[Any], s: Settings) -> list[Row]:
    return await _rows(
        conn,
        """
        SELECT o.id AS order_id, o.amount_cents,
               coalesce(sum(i.unit_price_cents), 0) AS items_total
          FROM orders.orders o LEFT JOIN orders.order_items i ON i.order_id = o.id
         GROUP BY o.id, o.amount_cents
        HAVING o.amount_cents <> coalesce(sum(i.unit_price_cents), 0)
        """,
    )


SQL_CHECKS: dict[str, Callable[[AsyncConnection[Any], Settings], Awaitable[list[Row]]]] = {
    "sold_within_capacity": sold_within_capacity,
    "no_seat_sold_twice": no_seat_sold_twice,
    "payments_settled": payments_settled,
    "no_stuck_orders": no_stuck_orders,
    "user_seat_limit": user_seat_limit,
    "order_amounts": order_amounts,
}


async def no_zombie_holds(redis: Any, s: Settings) -> list[Row]:
    """Invariant 5: holds whose lease ended longer ago than one sweeper interval (plus
    slack). The expiry sorted sets are the source; the sweeper removes entries as it
    releases holds."""
    cutoff_ms = (time.time() - s.hold_sweep_interval_s - s.slack_s) * 1000
    zombies: list[Row] = []
    for entry in await redis.smembers("inventory:sections"):
        entry = entry.decode() if isinstance(entry, bytes) else entry
        event_id, section = entry.split(":", 1)
        key = f"{{evt:{event_id}:sec:{section}}}:expiry"
        for hold, score in await redis.zrangebyscore(key, "-inf", cutoff_ms, withscores=True):
            hold = hold.decode() if isinstance(hold, bytes) else hold
            zombies.append(
                {"hold_id": hold, "lease_ended_s_ago": round(time.time() - score / 1000, 1)}
            )
    return zombies
