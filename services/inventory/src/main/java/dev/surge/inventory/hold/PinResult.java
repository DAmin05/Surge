package dev.surge.inventory.hold;

import java.util.List;

public sealed interface PinResult {

    record Pinned(long eventId, String section, List<Long> seatIds, long leaseExpiresAtMs) implements PinResult {}

    record Rejected(Reason reason) implements PinResult {}

    enum Reason { NOT_FOUND, WRONG_OWNER, REBUILDING }
}
