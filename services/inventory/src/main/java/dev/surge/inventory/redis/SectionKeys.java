package dev.surge.inventory.redis;

import java.util.ArrayList;
import java.util.List;

/**
 * Keys of one section. They all share the hash tag {@code {evt:<id>:sec:<section>}}, so
 * they live on one Redis Cluster shard and one Lua script can touch any of them.
 */
public record SectionKeys(long eventId, String section) {

    public static final String SECTIONS_REGISTRY = "inventory:sections";

    public String tag() {
        return "{evt:" + eventId + ":sec:" + section + "}";
    }

    public String epoch() { return tag() + ":epoch"; }
    public String seq() { return tag() + ":seq"; }
    public String expiry() { return tag() + ":expiry"; }
    public String sold() { return tag() + ":sold"; }
    public String held() { return tag() + ":held"; }
    public String hold(String holdId) { return tag() + ":hold:" + holdId; }
    public String seat(long seatId) { return tag() + ":seat:" + seatId; }

    /** The layout every mutating script takes: see resources/lua/README.md. */
    public List<String> scriptKeys(String holdId, List<Long> seatIds) {
        var keys = new ArrayList<String>(6 + seatIds.size());
        keys.addAll(List.of(epoch(), seq(), expiry(), sold(), held(), hold(holdId)));
        seatIds.forEach(id -> keys.add(seat(id)));
        return keys;
    }

    /** Member of {@link #SECTIONS_REGISTRY}, which tells the sweeper where to look. */
    public String registryEntry() {
        return eventId + ":" + section;
    }

    public static SectionKeys fromRegistryEntry(String entry) {
        int i = entry.indexOf(':');
        return new SectionKeys(Long.parseLong(entry.substring(0, i)), entry.substring(i + 1));
    }
}
