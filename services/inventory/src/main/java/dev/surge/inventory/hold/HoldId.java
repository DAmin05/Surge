package dev.surge.inventory.hold;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A hold id names its section ({@code <eventId>:<section>:<uuid>}), so any instance can
 * find the hold's shard from the id alone, with no lookup table.
 */
public record HoldId(long eventId, String section, String value) {

    public static final Pattern SECTION = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    public static HoldId generate(long eventId, String section) {
        return new HoldId(eventId, section, eventId + ":" + section + ":" + UUID.randomUUID());
    }

    /** @throws IllegalArgumentException if the id is malformed */
    public static HoldId parse(String value) {
        String[] parts = value.split(":", 3);
        if (parts.length != 3 || !SECTION.matcher(parts[1]).matches()) {
            throw new IllegalArgumentException("malformed hold id");
        }
        try {
            UUID.fromString(parts[2]);
            return new HoldId(Long.parseLong(parts[0]), parts[1], value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("malformed hold id", e);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
