package dev.surge.order.inventory;

import java.util.List;

public interface InventoryClient {

    sealed interface PinOutcome {
        record Pinned(long eventId, String section, List<Long> seatIds, long leaseExpiresAtMs) implements PinOutcome {}

        record Rejected(Reason reason) implements PinOutcome {}

        enum Reason { NOT_FOUND, WRONG_OWNER, REBUILDING }
    }

    /** Thrown when Inventory can't be reached; nothing was claimed. */
    final class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Validates the hold and extends its lease past the payment timeout. */
    PinOutcome validateAndPin(String holdId, String userId);

    /** Best effort; a hold that isn't released expires on its own. */
    void release(String holdId, String userId);
}
