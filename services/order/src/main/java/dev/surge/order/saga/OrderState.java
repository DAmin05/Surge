package dev.surge.order.saga;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The checkout saga as an explicit state machine. Transitions only move forward;
 * anything not listed here is a bug and is refused.
 *
 * <pre>
 * CREATED -> SEAT_RESERVED -> PAYMENT_PENDING -> CONFIRMED
 *                                  |
 *                                  +-> PAYMENT_FAILED -> SEAT_RELEASED -> CANCELLED
 * </pre>
 */
public enum OrderState {
    CREATED,
    SEAT_RESERVED,
    PAYMENT_PENDING,
    CONFIRMED,
    PAYMENT_FAILED,
    SEAT_RELEASED,
    CANCELLED,
    /** Reserved for orders recorded without a successful claim; not written today. */
    FAILED;

    private static final Map<OrderState, Set<OrderState>> NEXT = Map.of(
            CREATED, EnumSet.of(SEAT_RESERVED),
            SEAT_RESERVED, EnumSet.of(PAYMENT_PENDING),
            PAYMENT_PENDING, EnumSet.of(CONFIRMED, PAYMENT_FAILED),
            PAYMENT_FAILED, EnumSet.of(SEAT_RELEASED),
            SEAT_RELEASED, EnumSet.of(CANCELLED));

    public boolean canMoveTo(OrderState next) {
        return NEXT.getOrDefault(this, Set.of()).contains(next);
    }

    public boolean isTerminal() {
        return this == CONFIRMED || this == CANCELLED || this == FAILED;
    }

    /** Cancelled or on its way there: a payment arriving now must be refunded. */
    public boolean isCompensating() {
        return this == PAYMENT_FAILED || this == SEAT_RELEASED || this == CANCELLED;
    }
}
