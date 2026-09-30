package dev.surge.inventory.events;

import java.util.List;
import java.util.function.Consumer;

import dev.surge.contracts.events.SeatStatus;

/**
 * The durable record of sold seats: the compacted {@code seat-status} topic. Redis sold
 * markers can be lost in a failover; the ledger can't, so a section's sold set is
 * rebuilt from it before the section accepts holds again.
 */
public interface SoldLedger {

    /** Records sold seats and returns only once the broker has acknowledged them. */
    void record(List<SeatStatus> sold);

    /** Calls {@code sold} for every recorded sold seat in the section. */
    void replay(long eventId, String section, Consumer<Long> sold);

    /** For running without Kafka (tests, local tinkering): nothing is durable. */
    SoldLedger NONE = new SoldLedger() {
        @Override
        public void record(List<SeatStatus> sold) { }

        @Override
        public void replay(long eventId, String section, Consumer<Long> sold) { }
    };
}
