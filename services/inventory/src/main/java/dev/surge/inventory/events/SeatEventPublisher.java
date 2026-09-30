package dev.surge.inventory.events;

import java.util.List;

import dev.surge.contracts.events.SeatEvent;

/**
 * Publishes seat changes for the live map. Inventory has no database, so there is no
 * outbox: an event can be lost, and clients self-heal by re-snapshotting on a seq gap
 * (ADR 0004). Implementations must not block the caller on the broker.
 */
public interface SeatEventPublisher {

    void publish(List<SeatEvent> events);
}
