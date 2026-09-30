package dev.surge.inventory.hold;

import static dev.surge.inventory.redis.LuaScripts.Script.EXPIRED;
import static dev.surge.inventory.redis.LuaScripts.Script.HOLD;
import static dev.surge.inventory.redis.LuaScripts.Script.PIN;
import static dev.surge.inventory.redis.LuaScripts.Script.RELEASE;
import static dev.surge.inventory.redis.LuaScripts.Script.SNAPSHOT;
import static dev.surge.inventory.redis.LuaScripts.Script.SOLD;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;

import dev.surge.contracts.events.SeatEvent;
import dev.surge.contracts.events.SeatStatus;
import dev.surge.inventory.config.InventoryProperties;
import dev.surge.inventory.events.SeatEventPublisher;
import dev.surge.inventory.events.SoldLedger;
import dev.surge.inventory.redis.LuaScripts;
import dev.surge.inventory.redis.SectionKeys;
import io.lettuce.core.Range;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

/**
 * Seat holds: the fast, best-effort layer in front of the Postgres claim (ADR 0001).
 * Every seat change runs in one Lua script that also increments the section's
 * sequence, and is then published as a {@link SeatEvent}.
 */
@Service
public class HoldService {

    private final RedisClusterCommands<String, String> redis;
    private final LuaScripts scripts;
    private final EpochRebuilder epochs;
    private final SeatEventPublisher events;
    private final SoldLedger ledger;
    private final InventoryProperties props;
    private final MeterRegistry meters;

    public HoldService(RedisClusterCommands<String, String> redis, LuaScripts scripts, EpochRebuilder epochs,
            SeatEventPublisher events, SoldLedger ledger, InventoryProperties props, MeterRegistry meters) {
        this.redis = redis;
        this.scripts = scripts;
        this.epochs = epochs;
        this.events = events;
        this.ledger = ledger;
        this.props = props;
        this.meters = meters;
    }

    /**
     * Holds all seats or none. Seats must be distinct and in one section.
     *
     * @throws IllegalArgumentException on a malformed request
     */
    public HoldResult hold(String userId, long eventId, String section, List<Long> seatIds) {
        validate(userId, section, seatIds);
        List<Long> seats = seatIds.stream().sorted().toList();
        var keys = new SectionKeys(eventId, section);

        String userKey = userHeldKey(userId, eventId);
        redis.zremrangebyscore(userKey, Range.create(Double.NEGATIVE_INFINITY, (double) System.currentTimeMillis()));
        if (redis.zcard(userKey) + seats.size() > props.maxSeatsPerUser()) {
            return rejected(HoldResult.Reason.USER_LIMIT, null);
        }

        HoldId holdId = HoldId.generate(eventId, section);
        List<String> args = args(holdId.value(), userId, props.holdLease().toMillis(), seats);
        List<Object> r = scripts.run(HOLD, keys.scriptKeys(holdId.value(), seats), args);
        if ("REBUILDING".equals(r.getFirst())) {
            epochs.rebuild(keys);
            r = scripts.run(HOLD, keys.scriptKeys(holdId.value(), seats), args);
        }

        switch ((String) r.getFirst()) {
            case "OK" -> { }
            case "TAKEN" -> { return rejected(HoldResult.Reason.SEAT_TAKEN, Long.parseLong((String) r.get(1))); }
            case "SOLD" -> { return rejected(HoldResult.Reason.SEAT_SOLD, Long.parseLong((String) r.get(1))); }
            case "REBUILDING" -> { return rejected(HoldResult.Reason.REBUILDING, null); }
            default -> throw new IllegalStateException("hold script returned " + r);
        }

        String epoch = (String) r.get(1);
        long exp = (Long) r.get(2);
        redis.sadd(SectionKeys.SECTIONS_REGISTRY, keys.registryEntry());
        trackUserSeats(userKey, exp, section, seats);

        var out = new ArrayList<SeatEvent>(seats.size());
        for (int i = 0; i < seats.size(); i++) {
            out.add(event(SeatEvent.Type.SEAT_HELD, keys, seats.get(i), epoch, (Long) r.get(3 + i),
                    holdId.value(), userId, exp));
        }
        events.publish(out);
        meters.counter("holds_total", "result", "held").increment();
        return new HoldResult.Held(holdId, exp, epoch);
    }

    /**
     * Validates that the hold exists and belongs to {@code userId}, then extends its
     * lease to now + T + grace. Runs before the Postgres claim, so a crash between the
     * claim and anything after it can't leave a claimed seat with a short lease.
     */
    public PinResult validateAndPin(String holdIdValue, String userId) {
        HoldId holdId = HoldId.parse(holdIdValue);
        var keys = new SectionKeys(holdId.eventId(), holdId.section());
        List<Long> seats = seatsOf(keys, holdId.value());
        if (seats.isEmpty()) {
            return pinRejected(PinResult.Reason.NOT_FOUND);
        }

        List<Object> r = scripts.run(PIN, keys.scriptKeys(holdId.value(), seats),
                args(holdId.value(), userId, props.pinExtension().toMillis(), seats));
        switch ((String) r.getFirst()) {
            case "OK" -> { }
            // Seat lists never change once written; a mismatch means the hold was
            // replaced under the same id, which can't happen with random ids.
            case "NOT_FOUND", "CHANGED" -> { return pinRejected(PinResult.Reason.NOT_FOUND); }
            case "WRONG_OWNER" -> { return pinRejected(PinResult.Reason.WRONG_OWNER); }
            case "REBUILDING" -> { return pinRejected(PinResult.Reason.REBUILDING); }
            default -> throw new IllegalStateException("pin script returned " + r);
        }

        String epoch = (String) r.get(1);
        long exp = (Long) r.get(2);
        trackUserSeats(userHeldKey(userId, holdId.eventId()), exp, holdId.section(), seats);
        var out = new ArrayList<SeatEvent>(seats.size());
        for (int i = 0; i < seats.size(); i++) {
            out.add(event(SeatEvent.Type.SEAT_HOLD_EXTENDED, keys, seats.get(i), epoch, (Long) r.get(3 + i),
                    holdId.value(), userId, exp));
        }
        events.publish(out);
        meters.counter("pins_total", "result", "pinned").increment();
        return new PinResult.Pinned(holdId.eventId(), holdId.section(), seats, exp);
    }

    /**
     * Releases a hold. {@code userId} null means the system (no owner check).
     *
     * @return true if the hold existed and was released
     */
    public boolean release(String holdIdValue, String userId) {
        return release(HoldId.parse(holdIdValue), userId, false) == ReleaseOutcome.RELEASED;
    }

    /**
     * An order was confirmed: its seats are sold for good. Records them in the durable
     * ledger first, then writes Redis sold markers and deletes the hold, so a retry
     * after a crash in between only repeats idempotent steps.
     */
    public void markSold(long orderId, long eventId, String section, List<Long> seatIds, String holdId) {
        long now = System.currentTimeMillis();
        ledger.record(seatIds.stream().map(s -> new SeatStatus(eventId, section, s, orderId, now)).toList());

        var keys = new SectionKeys(eventId, section);
        String hold = holdId == null ? "none" : holdId;
        List<Long> seats = seatIds.stream().sorted().toList();
        List<Object> r = scripts.run(SOLD, keys.scriptKeys(hold, seats), args(hold, "", "0", seats));

        long lapsed = (Long) r.get(2);
        if (lapsed > 0) {
            // The lease didn't outlive the saga: the map showed a sold seat as free for a while.
            meters.counter("holds_expired_while_reserved").increment(lapsed);
        }
        String epoch = (String) r.get(1);
        var out = new ArrayList<SeatEvent>();
        for (int i = 3; i + 1 < r.size(); i += 2) {
            out.add(event(SeatEvent.Type.SEAT_SOLD, keys, Long.parseLong((String) r.get(i)), epoch,
                    (Long) r.get(i + 1), null, null, null));
        }
        events.publish(out);
        // The buyer's best-effort limit entries age out at the lease end; the Postgres
        // claim counts confirmed seats authoritatively.
    }

    /** Releases every hold in every known section whose lease has lapsed. */
    public int sweepExpired() {
        int released = 0;
        for (String entry : redis.smembers(SectionKeys.SECTIONS_REGISTRY)) {
            var keys = SectionKeys.fromRegistryEntry(entry);
            List<Object> ids = scripts.run(EXPIRED, List.of(keys.expiry()),
                    List.of(Integer.toString(props.sweepBatch())));
            for (Object id : ids) {
                if (release(HoldId.parse((String) id), null, true) == ReleaseOutcome.RELEASED) {
                    released++;
                }
            }
        }
        if (released > 0) {
            meters.counter("holds_expired_total").increment(released);
        }
        return released;
    }

    public Snapshot snapshot(long eventId, String section) {
        if (!HoldId.SECTION.matcher(section).matches()) {
            throw new IllegalArgumentException("bad section");
        }
        var keys = new SectionKeys(eventId, section);
        List<Object> r = scripts.run(SNAPSHOT, List.of(keys.epoch(), keys.seq(), keys.sold(), keys.held()), List.of());
        return new Snapshot(eventId, section, (String) r.get(0), (Long) r.get(1), ids(r.get(2)), ids(r.get(3)));
    }

    // ------------------------------------------------------------------ internals

    private enum ReleaseOutcome { RELEASED, NOT_FOUND, WRONG_OWNER, ALIVE }

    private ReleaseOutcome release(HoldId holdId, String userId, boolean expiredOnly) {
        var keys = new SectionKeys(holdId.eventId(), holdId.section());
        List<String> hold = redis.hmget(keys.hold(holdId.value()), "user", "seats").stream()
                .map(kv -> kv.getValueOrElse(null)).toList();
        String owner = hold.get(0);
        List<Long> seats = parseSeats(hold.get(1));

        List<Object> r = scripts.run(RELEASE, keys.scriptKeys(holdId.value(), seats),
                args(holdId.value(), userId == null ? "" : userId, expiredOnly ? "expired" : "any", seats));
        switch ((String) r.getFirst()) {
            case "OK" -> { }
            case "NOT_FOUND" -> { return ReleaseOutcome.NOT_FOUND; }
            case "WRONG_OWNER" -> { return ReleaseOutcome.WRONG_OWNER; }
            case "ALIVE" -> { return ReleaseOutcome.ALIVE; }
            default -> throw new IllegalStateException("release script returned " + r);
        }

        if (owner != null && !seats.isEmpty()) {
            redis.zrem(userHeldKey(owner, holdId.eventId()),
                    seats.stream().map(s -> holdId.section() + ":" + s).toArray(String[]::new));
        }
        String epoch = (String) r.get(1);
        var out = new ArrayList<SeatEvent>();
        for (int i = 2; i + 1 < r.size(); i += 2) {
            out.add(event(SeatEvent.Type.SEAT_RELEASED, keys, Long.parseLong((String) r.get(i)), epoch,
                    (Long) r.get(i + 1), holdId.value(), userId, null));
        }
        events.publish(out);
        meters.counter("holds_released_total", "reason", expiredOnly ? "expired" : "released").increment();
        return ReleaseOutcome.RELEASED;
    }

    private List<Long> seatsOf(SectionKeys keys, String holdId) {
        return parseSeats(redis.hget(keys.hold(holdId), "seats"));
    }

    private static List<Long> parseSeats(String csv) {
        if (csv == null || csv.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(Long::parseLong).toList();
    }

    private void validate(String userId, String section, List<Long> seatIds) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("missing user");
        }
        if (section == null || !HoldId.SECTION.matcher(section).matches()) {
            throw new IllegalArgumentException("bad section");
        }
        if (seatIds == null || seatIds.isEmpty() || seatIds.size() > props.maxSeatsPerOrder()) {
            throw new IllegalArgumentException("an order holds 1-" + props.maxSeatsPerOrder() + " seats");
        }
        if (new HashSet<>(seatIds).size() != seatIds.size() || seatIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("seat ids must be distinct and positive");
        }
    }

    @SuppressWarnings("unchecked")
    private void trackUserSeats(String userKey, long exp, String section, List<Long> seats) {
        ScoredValue<String>[] members = seats.stream()
                .map(s -> ScoredValue.just((double) exp, section + ":" + s))
                .toArray(ScoredValue[]::new);
        redis.zadd(userKey, members);
    }

    /** Per-user held seats for the best-effort limit; its own hash slot, not atomic with holds. */
    static String userHeldKey(String userId, long eventId) {
        return "{usr:" + userId + ":evt:" + eventId + "}:held";
    }

    private static List<String> args(String holdId, String userId, Object third, List<Long> seats) {
        return Stream.concat(Stream.of(holdId, userId, third.toString()), seats.stream().map(String::valueOf))
                .toList();
    }

    private static List<Long> ids(Object members) {
        return ((List<?>) members).stream().map(m -> Long.parseLong((String) m)).sorted().toList();
    }

    private static SeatEvent event(SeatEvent.Type type, SectionKeys keys, long seatId, String epoch, long seq,
            String holdId, String userId, Long exp) {
        return new SeatEvent(type, keys.eventId(), keys.section(), seatId, epoch, seq, holdId, userId, exp,
                System.currentTimeMillis());
    }

    private HoldResult rejected(HoldResult.Reason reason, Long seatId) {
        meters.counter("holds_total", "result", reason.name().toLowerCase()).increment();
        return new HoldResult.Rejected(reason, seatId);
    }

    private PinResult pinRejected(PinResult.Reason reason) {
        meters.counter("pins_total", "result", reason.name().toLowerCase()).increment();
        return new PinResult.Rejected(reason);
    }
}
