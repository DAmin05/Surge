package dev.surge.order.claim;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.surge.order.config.OrderProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

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

    private static final String ORDER_EVENTS_TOPIC = "order-events";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final OrderProperties props;
    private final JsonMapper json;
    private final MeterRegistry meters;

    public ClaimService(JdbcClient jdbc, TransactionTemplate tx, OrderProperties props, JsonMapper json,
            MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.props = props;
        this.json = json;
        this.meters = meters;
    }

    public ClaimResult claim(String userId, long eventId, String section, List<Long> seatIds) {
        Long[] seats = seatIds.stream().distinct().sorted().toArray(Long[]::new);
        if (seats.length != seatIds.size() || seats.length == 0) {
            throw new IllegalArgumentException("seat ids must be distinct and non-empty");
        }
        ClaimResult result;
        try {
            result = tx.execute(status -> claimInTransaction(userId, eventId, section, seats));
        } catch (Rollback r) {
            result = new ClaimResult.Rejected(r.reason);
        }
        String outcome = result instanceof ClaimResult.Rejected r ? r.reason().name().toLowerCase() : "claimed";
        meters.counter("claims_total", "result", outcome).increment();
        return result;
    }

    private ClaimResult.Claimed claimInTransaction(String userId, long eventId, String section, Long[] seats) {
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
                INSERT INTO orders (user_id, event_id, section, state, amount_cents, payment_key)
                VALUES (?, ?, ?, 'SEAT_RESERVED', ?, ?)
                RETURNING id""")
                .params(userId, eventId, section, amount, paymentKey)
                .query(Long.class).single();

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

        // 6. Ask for payment in the same transaction (transactional outbox).
        String payload = json.writeValueAsString(Map.of(
                "type", "PaymentRequested",
                "orderId", orderId,
                "paymentKey", paymentKey.toString(),
                "userId", userId,
                "amountCents", amount));
        jdbc.sql("INSERT INTO outbox (aggregate_id, topic, payload) VALUES (?, ?, ?::jsonb)")
                .params(Long.toString(orderId), ORDER_EVENTS_TOPIC, payload)
                .update();

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
