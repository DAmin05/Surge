package dev.surge.inventory.hold;

public sealed interface HoldResult {

    record Held(HoldId holdId, long expiresAtMs, String epoch) implements HoldResult {}

    /** @param seatId the seat that caused the rejection, when there is one */
    record Rejected(Reason reason, Long seatId) implements HoldResult {}

    enum Reason {
        SEAT_TAKEN,
        SEAT_SOLD,
        /** Best-effort per-user limit; Postgres enforces the real one at the claim. */
        USER_LIMIT,
        /** Section epoch missing; sold-set rebuild in progress. Retry shortly. */
        REBUILDING
    }
}
