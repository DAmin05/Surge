package dev.surge.contracts.events;

/**
 * A sold seat, on the compacted {@code seat-status} topic keyed by {@code seatId}. The
 * topic is the durable record of sold seats that Inventory replays to rebuild its Redis
 * sold set after a failover, without asking Postgres. Schema:
 * {@code schemas/seat-status.schema.json}.
 */
public record SeatStatus(long eventId, String section, long seatId, long orderId, long soldAtMs) {}
