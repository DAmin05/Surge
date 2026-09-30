package dev.surge.order.saga;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Order rows and their transitions. Callers must be inside a transaction; the methods
 * that lock say so.
 */
@Repository
public class OrderStore {

    public record Order(long id, String userId, long eventId, String section, OrderState state, long amountCents,
            UUID paymentKey, String holdId, boolean refundRequested) {}

    private final JdbcClient jdbc;

    public OrderStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Locks the order row. Always taken before any seat locks (see ClaimService). */
    public Optional<Order> lockByPaymentKey(UUID paymentKey) {
        return jdbc.sql("""
                SELECT id, user_id, event_id, section, state, amount_cents, payment_key, hold_id,
                       refund_requested_at IS NOT NULL AS refund_requested
                  FROM orders WHERE payment_key = ?
                   FOR UPDATE""")
                .param(paymentKey)
                .query((rs, i) -> new Order(rs.getLong("id"), rs.getString("user_id"), rs.getLong("event_id"),
                        rs.getString("section"), OrderState.valueOf(rs.getString("state")), rs.getLong("amount_cents"),
                        rs.getObject("payment_key", UUID.class), rs.getString("hold_id"),
                        rs.getBoolean("refund_requested")))
                .optional();
    }

    /**
     * Moves an order from {@code from} to {@code to} and records why. The update is
     * conditional on the current state, so a transition decided on stale data fails
     * instead of overwriting.
     *
     * @throws IllegalStateException if the transition isn't allowed or the order moved
     */
    public void transition(long orderId, OrderState from, OrderState to, String reason) {
        if (!from.canMoveTo(to)) {
            throw new IllegalStateException("illegal transition " + from + " -> " + to);
        }
        int n = jdbc.sql("UPDATE orders SET state = ?, updated_at = now() WHERE id = ? AND state = ?")
                .params(to.name(), orderId, from.name())
                .update();
        if (n != 1) {
            throw new IllegalStateException("order " + orderId + " is no longer " + from);
        }
        record(orderId, from, to, reason);
    }

    public void record(long orderId, OrderState from, OrderState to, String reason) {
        jdbc.sql("INSERT INTO order_transitions (order_id, from_state, to_state, reason) VALUES (?, ?, ?, ?)")
                .params(orderId, from == null ? null : from.name(), to.name(), reason)
                .update();
    }

    /**
     * Locks the order's seats in ascending id order, the same order the claim uses, so
     * confirmation and compensation can't deadlock with a concurrent claim.
     */
    public List<Long> lockSeats(long orderId) {
        return jdbc.sql("""
                SELECT s.id FROM seats s JOIN order_items oi ON oi.seat_id = s.id
                 WHERE oi.order_id = ?
                 ORDER BY s.id
                   FOR UPDATE OF s""")
                .param(orderId)
                .query(Long.class).list();
    }

    public void markRefundRequested(long orderId) {
        jdbc.sql("UPDATE orders SET refund_requested_at = now() WHERE id = ? AND refund_requested_at IS NULL")
                .param(orderId).update();
    }

    /** Orders waiting on payment for longer than {@code seconds}, oldest first. */
    public List<UUID> pendingOlderThan(long seconds, int limit) {
        return jdbc.sql("""
                SELECT payment_key FROM orders
                 WHERE state = 'PAYMENT_PENDING' AND updated_at < now() - make_interval(secs => ?)
                 ORDER BY updated_at
                 LIMIT ?""")
                .params(seconds, limit)
                .query(UUID.class).list();
    }
}
