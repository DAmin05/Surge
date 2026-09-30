package dev.surge.order.claim;

import java.util.List;
import java.util.UUID;

public sealed interface ClaimResult {

    record Claimed(long orderId, UUID paymentKey, long amountCents, List<Long> seatIds) implements ClaimResult {}

    record Rejected(Reason reason) implements ClaimResult {}

    enum Reason {
        /** Another order owns at least one seat. The whole claim rolled back. */
        SEAT_TAKEN,
        /** The user would exceed the per-event seat limit. */
        USER_LIMIT,
        /** A seat id doesn't exist in this event and section. */
        INVALID_SEATS,
        /** No price for this event and section. */
        UNKNOWN_SECTION
    }
}
