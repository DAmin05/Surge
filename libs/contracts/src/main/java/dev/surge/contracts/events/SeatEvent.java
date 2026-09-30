package dev.surge.contracts.events;

/**
 * One change to one seat, published to the {@code seat-events} topic keyed by
 * {@code eventId:section}. Schema: {@code schemas/seat-event.schema.json}.
 *
 * <p>{@code (epoch, seq)} order changes within a section: {@code seq} is incremented
 * by the same Lua script that made the change. A consumer that sees a gap in
 * {@code seq}, or a different {@code epoch}, must fetch a fresh snapshot. Epochs are
 * compared only for equality.
 *
 * @param holdId null for {@link Type#SEAT_SOLD}
 * @param userId null when the system made the change (sweeper, sale confirmation)
 * @param leaseExpiresAtMs lease end for HELD / HOLD_EXTENDED, otherwise null
 */
public record SeatEvent(
        Type type,
        long eventId,
        String section,
        long seatId,
        String epoch,
        long seq,
        String holdId,
        String userId,
        Long leaseExpiresAtMs,
        long occurredAtMs) {

    public enum Type {
        SEAT_HELD,
        SEAT_RELEASED,
        SEAT_HOLD_EXTENDED,
        SEAT_SOLD
    }

    /** Kafka record key: all changes to a section land on one partition, in order. */
    public String partitionKey() {
        return eventId + ":" + section;
    }
}
