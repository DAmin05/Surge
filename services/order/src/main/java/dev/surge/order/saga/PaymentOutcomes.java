package dev.surge.order.saga;

import java.util.List;
import java.util.UUID;

import dev.surge.contracts.events.OrderEvent;
import dev.surge.order.outbox.Outbox;
import dev.surge.order.saga.OrderStore.Order;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies a payment result to an order. The webhook is the authoritative source
 * (Stripe-style); the timeout sweeper also lands here after asking Payment directly.
 *
 * <p>Callbacks can be duplicated, delayed and reordered, so every case is decided by
 * the order's current state under a row lock, and transitions only move forward:
 * <ul>
 *   <li>success while pending: confirm (tickets, seats SOLD, OrderConfirmed);</li>
 *   <li>success when already confirmed: duplicate, ignored;</li>
 *   <li>success after cancellation: refund, requested exactly once;</li>
 *   <li>failure while pending, or timeout: compensate (seats released, cancelled);</li>
 *   <li>failure after confirmation: stale, ignored.</li>
 * </ul>
 */
@Service
public class PaymentOutcomes {

    public enum Result {
        CONFIRMED, CANCELLED, REFUND_REQUESTED, DUPLICATE, IGNORED_LATE_FAILURE, NOT_PENDING, UNKNOWN_PAYMENT
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final OrderStore orders;
    private final Outbox outbox;
    private final MeterRegistry meters;

    public PaymentOutcomes(JdbcClient jdbc, TransactionTemplate tx, OrderStore orders, Outbox outbox,
            MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.orders = orders;
        this.outbox = outbox;
        this.meters = meters;
    }

    public Result succeeded(UUID paymentKey, String source) {
        return count("succeeded", source, tx.execute(status -> orders.lockByPaymentKey(paymentKey)
                .map(order -> switch (order.state()) {
                    case PAYMENT_PENDING -> confirm(order, source);
                    case CONFIRMED -> Result.DUPLICATE;
                    case PAYMENT_FAILED, SEAT_RELEASED, CANCELLED -> requestRefund(order);
                    // The payment is requested in the same transaction that makes the
                    // order PAYMENT_PENDING, so these states can't see a payment.
                    case CREATED, SEAT_RESERVED, FAILED ->
                            throw new IllegalStateException("payment for order " + order.id() + " in " + order.state());
                })
                .orElse(Result.UNKNOWN_PAYMENT)));
    }

    public Result failed(UUID paymentKey, String source) {
        return count("failed", source, tx.execute(status -> orders.lockByPaymentKey(paymentKey)
                .map(order -> switch (order.state()) {
                    case PAYMENT_PENDING -> compensate(order, "payment failed (" + source + ")");
                    case CONFIRMED -> Result.IGNORED_LATE_FAILURE;
                    default -> Result.DUPLICATE;
                })
                .orElse(Result.UNKNOWN_PAYMENT)));
    }

    /**
     * Timeout: Payment says the charge is still pending or never started. Cancel; if
     * the charge succeeds later, its callback triggers a refund.
     */
    public Result timedOut(UUID paymentKey) {
        return count("timeout", "sweeper", tx.execute(status -> orders.lockByPaymentKey(paymentKey)
                .map(order -> order.state() == OrderState.PAYMENT_PENDING
                        ? compensate(order, "payment timeout")
                        : Result.NOT_PENDING)
                .orElse(Result.UNKNOWN_PAYMENT)));
    }

    private Result confirm(Order order, String source) {
        List<Long> seats = orders.lockSeats(order.id());
        int sold = jdbc.sql("""
                UPDATE seats SET status = 'SOLD', version = version + 1
                 WHERE order_id = ? AND status = 'RESERVED'""")
                .param(order.id()).update();
        if (sold != seats.size()) {
            // Seats stay RESERVED for this order until the saga ends; anything else is a bug.
            throw new IllegalStateException("order " + order.id() + " owns " + sold + " of " + seats.size() + " seats");
        }
        for (Long seat : seats) {
            // tickets.seat_id is UNIQUE: the last line of defense against a double sale.
            jdbc.sql("INSERT INTO tickets (seat_id, order_id) VALUES (?, ?)").params(seat, order.id()).update();
        }
        orders.transition(order.id(), OrderState.PAYMENT_PENDING, OrderState.CONFIRMED, "payment succeeded (" + source + ")");
        outbox.append(OrderEvent.confirmed(order.id(), order.userId(), order.eventId(), order.section(), seats,
                order.holdId()));
        return Result.CONFIRMED;
    }

    private Result compensate(Order order, String reason) {
        List<Long> seats = orders.lockSeats(order.id());
        orders.transition(order.id(), OrderState.PAYMENT_PENDING, OrderState.PAYMENT_FAILED, reason);
        jdbc.sql("""
                UPDATE seats SET status = 'AVAILABLE', order_id = NULL, version = version + 1
                 WHERE order_id = ? AND status = 'RESERVED'""")
                .param(order.id()).update();
        orders.transition(order.id(), OrderState.PAYMENT_FAILED, OrderState.SEAT_RELEASED, "seats back on sale");
        orders.transition(order.id(), OrderState.SEAT_RELEASED, OrderState.CANCELLED, "compensated");
        // Redis holds are released only now, after the order is terminal.
        outbox.append(OrderEvent.cancelled(order.id(), order.userId(), order.eventId(), order.section(), seats,
                order.holdId(), reason));
        return Result.CANCELLED;
    }

    private Result requestRefund(Order order) {
        if (order.refundRequested()) {
            return Result.DUPLICATE;
        }
        orders.markRefundRequested(order.id());
        outbox.append(OrderEvent.refundRequested(order.id(), order.userId(), order.paymentKey().toString(),
                order.amountCents()));
        return Result.REFUND_REQUESTED;
    }

    private Result count(String outcome, String source, Result result) {
        meters.counter("payment_outcomes_total", "outcome", outcome, "source", source,
                "result", result.name().toLowerCase()).increment();
        return result;
    }
}
