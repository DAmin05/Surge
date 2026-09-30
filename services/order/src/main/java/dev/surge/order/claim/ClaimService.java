package dev.surge.order.claim;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import dev.surge.contracts.events.OrderEvent;
import dev.surge.order.config.OrderProperties;
import dev.surge.order.outbox.Outbox;
import dev.surge.order.saga.OrderState;
import dev.surge.order.saga.OrderStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The seat claim: where "never oversell" is actually decided (ADR 0001).
 *
 * <p>Lock order is fixed and must stay identical on every code path that locks seats,
 * or concurrent orders can deadlock:
 * <ol>
 *   <li>the per-(event, user) advisory lock, which serializes one user's checkouts so
 *       the seat-limit count can't be raced;</li>
 *   <li>the seat rows, {@code FOR UPDATE} in ascending id order.</li>
 * </ol>
 * Then the conditional update decides: fewer rows than seats means another order owns
 * one of them, and the whole transaction rolls back before anything is charged.
 */
@Service
public class ClaimService {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final OrderStore orders;
    private final Outbox outbox;
    private final OrderProperties props;
    private final MeterRegistry meters;

    public ClaimService(JdbcClient jdbc, TransactionTemplate tx, OrderStore orders, Outbox outbox,
            OrderProperties props, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.orders = orders;
        this.outbox = outbox;
        this.props = props;
        this.meters = meters;
    }

    public ClaimResult claim(String userId, long eventId, String section, List<Long> seatIds) {
        return claim(userId, eventId, section, seatIds, null, claimed -> { });
    }

    /**
     * @param holdId        the Redis hold behind this claim, released or deleted when
     *                      the saga ends
     * @param beforeCommit  runs inside the claim transaction after a successful claim;
     *                      throwing from it rolls the claim back (used to commit the
     *                      idempotent response atomically with the order)
     */
    public ClaimResult claim(String userId, long eventId, String section, List<Long> seatIds, String holdId,
            Consumer<ClaimResult.Claimed> beforeCommit) {
        Long[] seats = seatIds.stream().distinct().sorted().toArray(Long[]::new);
        if (seats.length != seatIds.size() || seats.length == 0) {
            throw new IllegalArgumentException("seat ids must be distinct and non-empty");
        }
        ClaimResult result;
        try {
            result = tx.execute(status -> {
                ClaimResult.Claimed claimed = claimInTransaction(userId, eventId, section, seats, holdId);
                beforeCommit.accept(claimed);
                return claimed;
            });
        } catch (Rollback r) {
            result = new ClaimResult.Rejected(r.reason);
        }
        String outcome = result instanceof ClaimResult.Rejected r ? r.reason().name().toLowerCase() : "claimed";
        meters.counter("claims_total", "result", outcome).increment();
        return result;
    }

    private ClaimResult.Claimed claimInTransaction(String userId, long eventId, String section, Long[] seats,
            String holdId) {
        // 1. Serialize this user's checkouts for this event.
        jdbc.sql("SELECT pg_advisory_xact_lock(?, hashtext(?))")
                .params(Math.toIntExact(eventId), userId)
                .query((rs, i) -> 1).list();

        // 2. Lock the seats in id order. Rows outside this event/section don't match.
        int found = jdbc.sql("""
                SELECT id FROM seats
                 WHERE id = ANY(?) AND event_id = ? AND section = ?
                 ORDER BY id
                   FOR UPDATE""")
                .params(seats, eventId, section)
                .query(Long.class).list().size();
        if (found != seats.length) {
            throw new Rollback(ClaimResult.Reason.INVALID_SEATS);
        }

        // 3. Authoritative per-user limit. Counts every order that could still become a
        //    sale, so two concurrent checkouts can't each see room for their seats.
        long alreadyHeld = jdbc.sql("""
                SELECT count(*) FROM order_items oi
                  JOIN orders o ON o.id = oi.order_id
                 WHERE o.event_id = ? AND o.user_id = ?
                   AND o.state NOT IN ('CANCELLED', 'FAILED')""")
                .params(eventId, userId)
                .query(Long.class).single();
        if (alreadyHeld + seats.length > props.maxSeatsPerUser()) {
            throw new Rollback(ClaimResult.Reason.USER_LIMIT);
        }

        Long unitPrice = jdbc.sql("SELECT price_cents FROM section_prices WHERE event_id = ? AND section = ?")
                .params(eventId, section)
                .query(Long.class).optional().orElseThrow(() -> new Rollback(ClaimResult.Reason.UNKNOWN_SECTION));
        long amount = unitPrice * seats.length;

        // 4. The order row first: seats.order_id references it.
        UUID paymentKey = UUID.randomUUID();
        long orderId = jdbc.sql("""
                INSERT INTO orders (user_id, event_id, section, state, amount_cents, payment_key, hold_id)
                VALUES (?, ?, ?, 'CREATED', ?, ?, ?)
                RETURNING id""")
                .params(userId, eventId, section, amount, paymentKey, holdId)
                .query(Long.class).single();
        orders.record(orderId, null, OrderState.CREATED, "checkout");

        // 5. The decision. Fewer rows than seats: someone else owns one. Roll back all.
        int claimed = jdbc.sql("""
                UPDATE seats SET status = 'RESERVED', order_id = ?, version = version + 1
                 WHERE id = ANY(?) AND status = 'AVAILABLE'""")
                .params(orderId, seats)
                .update();
        if (claimed != seats.length) {
            throw new Rollback(ClaimResult.Reason.SEAT_TAKEN);
        }

        for (Long seat : seats) {
            jdbc.sql("INSERT INTO order_items (order_id, seat_id, unit_price_cents) VALUES (?, ?, ?)")
                    .params(orderId, seat, unitPrice)
                    .update();
        }

        orders.transition(orderId, OrderState.CREATED, OrderState.SEAT_RESERVED, "seats claimed");

        // 6. Ask for payment in the same transaction (transactional outbox). T, the
        //    payment timeout, runs from here.
        outbox.append(OrderEvent.paymentRequested(orderId, userId, paymentKey.toString(), amount));
        orders.transition(orderId, OrderState.SEAT_RESERVED, OrderState.PAYMENT_PENDING, "payment requested");

        return new ClaimResult.Claimed(orderId, paymentKey, amount, List.of(seats));
    }

    /** Unwinds the transaction; carries the reason out. */
    private static final class Rollback extends RuntimeException {
        final ClaimResult.Reason reason;

        Rollback(ClaimResult.Reason reason) {
            super(reason.name(), null, false, false);
            this.reason = reason;
        }
    }
}
