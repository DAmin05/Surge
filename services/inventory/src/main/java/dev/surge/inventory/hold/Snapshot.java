package dev.surge.inventory.hold;

import java.util.List;

/** Section state at one {@code (epoch, seq)}; clients apply only later events. */
public record Snapshot(long eventId, String section, String epoch, long seq, List<Long> sold, List<Long> held) {}
