package dev.surge.contracts.events;

import java.util.List;

/**
 * Events on the {@code order-events} topic, keyed by order id, written through Order's
 * transactional outbox. Delivery is at-least-once and, with several relays, not
 * strictly ordered per order: consumers must be idempotent. Schema:
 * {@code schemas/order-event.schema.json}.
 *
 * <p>Fields a type doesn't use are null.
 */
public record OrderEvent(
        Type type,
        long orderId,
        String userId,
        Long eventId,
        String section,
        List<Long> seatIds,
        String holdId,
        String paymentKey,
        Long amountCents,
        String reason) {

    public enum Type {
        /** Ask Payment to charge. Carries paymentKey, amountCents, userId. */
        PAYMENT_REQUESTED,
        /** Tickets issued. Carries eventId, section, seatIds, holdId. */
        ORDER_CONFIRMED,
        /** Seats released in Postgres. Carries eventId, section, seatIds, holdId, reason. */
        ORDER_CANCELLED,
        /** Payment captured after the order was cancelled. Carries paymentKey, amountCents. */
        REFUND_REQUESTED
    }

    public static OrderEvent paymentRequested(long orderId, String userId, String paymentKey, long amountCents) {
        return new OrderEvent(Type.PAYMENT_REQUESTED, orderId, userId, null, null, null, null, paymentKey, amountCents,
                null);
    }

    public static OrderEvent confirmed(long orderId, String userId, long eventId, String section, List<Long> seatIds,
            String holdId) {
        return new OrderEvent(Type.ORDER_CONFIRMED, orderId, userId, eventId, section, seatIds, holdId, null, null,
                null);
    }

    public static OrderEvent cancelled(long orderId, String userId, long eventId, String section, List<Long> seatIds,
            String holdId, String reason) {
        return new OrderEvent(Type.ORDER_CANCELLED, orderId, userId, eventId, section, seatIds, holdId, null, null,
                reason);
    }

    public static OrderEvent refundRequested(long orderId, String userId, String paymentKey, long amountCents) {
        return new OrderEvent(Type.REFUND_REQUESTED, orderId, userId, null, null, null, null, paymentKey, amountCents,
                null);
    }
}
