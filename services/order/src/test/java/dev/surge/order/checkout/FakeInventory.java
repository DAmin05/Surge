package dev.surge.order.checkout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.surge.order.inventory.InventoryClient;

/** Inventory stand-in: holds registered by the test, pins and releases recorded. */
public class FakeInventory implements InventoryClient {

    public record Hold(String userId, long eventId, String section, List<Long> seatIds) {}

    public final Map<String, Hold> holds = new ConcurrentHashMap<>();
    public final List<String> pinned = new CopyOnWriteArrayList<>();
    public final List<String> released = new CopyOnWriteArrayList<>();
    public volatile PinOutcome.Reason forcedRejection;

    @Override
    public PinOutcome validateAndPin(String holdId, String userId) {
        if (forcedRejection != null) {
            return new PinOutcome.Rejected(forcedRejection);
        }
        Hold h = holds.get(holdId);
        if (h == null) {
            return new PinOutcome.Rejected(PinOutcome.Reason.NOT_FOUND);
        }
        if (!h.userId().equals(userId)) {
            return new PinOutcome.Rejected(PinOutcome.Reason.WRONG_OWNER);
        }
        pinned.add(holdId);
        return new PinOutcome.Pinned(h.eventId(), h.section(), h.seatIds(), System.currentTimeMillis() + 150_000);
    }

    @Override
    public void release(String holdId, String userId) {
        released.add(holdId);
    }
}
