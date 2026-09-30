"""payments.payments: one row per payment_key. Every status change is conditional,
so a charge or refund happens at most once however often it's requested."""

from typing import Protocol

from psycopg_pool import AsyncConnectionPool


class Store(Protocol):
    async def create_if_absent(
        self, payment_key: str, order_id: int, amount_cents: int
    ) -> bool: ...
    async def settle(self, payment_key: str, status: str) -> bool: ...
    async def refund(self, payment_key: str) -> bool: ...
    async def status(self, payment_key: str) -> str | None: ...
    async def pending(self) -> list[str]: ...
    async def order_of(self, payment_key: str) -> tuple[int, int] | None: ...


class PgStore:
    def __init__(self, pool: AsyncConnectionPool):
        self._pool = pool

    async def _execute(self, sql: str, *params: object) -> int:
        async with self._pool.connection() as conn:
            cur = await conn.execute(sql, params)
            return cur.rowcount

    async def create_if_absent(self, payment_key: str, order_id: int, amount_cents: int) -> bool:
        """True only for the call that created the row: the only one allowed to charge."""
        n = await self._execute(
            "INSERT INTO payments (payment_key, order_id, amount_cents, status)"
            " VALUES (%s, %s, %s, 'PENDING') ON CONFLICT (payment_key) DO NOTHING",
            payment_key,
            order_id,
            amount_cents,
        )
        return n == 1

    async def settle(self, payment_key: str, status: str) -> bool:
        n = await self._execute(
            "UPDATE payments SET status = %s, updated_at = now()"
            " WHERE payment_key = %s AND status = 'PENDING'",
            status,
            payment_key,
        )
        return n == 1

    async def refund(self, payment_key: str) -> bool:
        n = await self._execute(
            "UPDATE payments SET status = 'REFUNDED', updated_at = now()"
            " WHERE payment_key = %s AND status = 'CAPTURED'",
            payment_key,
        )
        return n == 1

    async def status(self, payment_key: str) -> str | None:
        async with self._pool.connection() as conn:
            cur = await conn.execute(
                "SELECT status FROM payments WHERE payment_key = %s", (payment_key,)
            )
            row = await cur.fetchone()
            return row[0] if row else None

    async def pending(self) -> list[str]:
        async with self._pool.connection() as conn:
            cur = await conn.execute(
                "SELECT payment_key::text FROM payments"
                " WHERE status = 'PENDING' ORDER BY created_at"
            )
            return [r[0] for r in await cur.fetchall()]

    async def order_of(self, payment_key: str) -> tuple[int, int] | None:
        async with self._pool.connection() as conn:
            cur = await conn.execute(
                "SELECT order_id, amount_cents FROM payments WHERE payment_key = %s", (payment_key,)
            )
            row = await cur.fetchone()
            return (row[0], row[1]) if row else None
