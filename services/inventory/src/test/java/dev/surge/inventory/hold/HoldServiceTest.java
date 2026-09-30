package dev.surge.inventory.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import dev.surge.contracts.events.SeatEvent;
import dev.surge.contracts.events.SeatEvent.Type;
import dev.surge.inventory.RedisTestSupport;
import dev.surge.inventory.RedisTestSupport.RecordingPublisher;
import dev.surge.inventory.config.InventoryProperties;
import dev.surge.inventory.redis.LuaScripts;
import dev.surge.inventory.redis.SectionKeys;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HoldServiceTest {

    private static final AtomicLong EVENT_IDS = new AtomicLong(1000);

    private final RedisClusterCommands<String, String> redis = RedisTestSupport.connect();
    private final RecordingPublisher published = new RecordingPublisher();
    private long eventId;

    @BeforeEach
    void freshEvent() {
        eventId = EVENT_IDS.incrementAndGet();
    }

    private HoldService service(InventoryProperties props) {
        return new HoldService(redis, new LuaScripts(redis), new EpochRebuilder(redis), published, props,
                new SimpleMeterRegistry());
    }

    private HoldService service() {
        return service(RedisTestSupport.props(Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofSeconds(30)));
    }

    @Test
    void thousandBuyersRaceForOneSeatAndExactlyOneHoldsIt() throws Exception {
        HoldService holds = service();
        int buyers = 1000;
        var results = new ConcurrentLinkedQueue<HoldResult>();
        var start = new CountDownLatch(1);

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < buyers; i++) {
                String user = "user-" + i;
                pool.submit(() -> {
                    start.await();
                    results.add(holds.hold(user, eventId, "A", List.of(17L)));
                    return null;
                });
            }
            start.countDown();
        }

        assertThat(results).hasSize(buyers);
        assertThat(results).filteredOn(r -> r instanceof HoldResult.Held).hasSize(1);
        assertThat(results).filteredOn(r -> r instanceof HoldResult.Rejected rej
                && rej.reason() == HoldResult.Reason.SEAT_TAKEN && rej.seatId() == 17L).hasSize(buyers - 1);
        assertThat(published.events).singleElement().extracting(SeatEvent::type).isEqualTo(Type.SEAT_HELD);
    }

    @Test
    void multiSeatHoldIsAllOrNothing() {
        HoldService holds = service();
        assertThat(holds.hold("alice", eventId, "A", List.of(1L, 2L))).isInstanceOf(HoldResult.Held.class);

        var bob = holds.hold("bob", eventId, "A", List.of(3L, 2L));
        assertThat(bob).isEqualTo(new HoldResult.Rejected(HoldResult.Reason.SEAT_TAKEN, 2L));
        // Seat 3 was not held as a side effect of Bob's failed attempt.
        assertThat(holds.hold("carol", eventId, "A", List.of(3L))).isInstanceOf(HoldResult.Held.class);
    }

    @Test
    void everySeatChangeGetsTheNextSequenceNumberInOneEpoch() {
        HoldService holds = service();
        var held = (HoldResult.Held) holds.hold("alice", eventId, "A", List.of(5L, 6L));
        holds.validateAndPin(held.holdId().value(), "alice");
        holds.release(held.holdId().value(), "alice");

        assertThat(published.events).extracting(SeatEvent::type).containsExactly(
                Type.SEAT_HELD, Type.SEAT_HELD,
                Type.SEAT_HOLD_EXTENDED, Type.SEAT_HOLD_EXTENDED,
                Type.SEAT_RELEASED, Type.SEAT_RELEASED);
        assertThat(published.events).extracting(SeatEvent::seq).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(published.events).extracting(SeatEvent::epoch).containsOnly(held.epoch());

        var snap = holds.snapshot(eventId, "A");
        assertThat(snap.epoch()).isEqualTo(held.epoch());
        assertThat(snap.seq()).isEqualTo(6);
        assertThat(snap.held()).isEmpty();
    }

    @Test
    void pinExtendsTheLeaseToPaymentTimeoutPlusGraceAndChecksTheOwner() {
        HoldService holds = service(RedisTestSupport.props(Duration.ofSeconds(10), Duration.ofMinutes(2),
                Duration.ofSeconds(30)));
        var held = (HoldResult.Held) holds.hold("alice", eventId, "B", List.of(9L, 8L));

        assertThat(holds.validateAndPin(held.holdId().value(), "mallory"))
                .isEqualTo(new PinResult.Rejected(PinResult.Reason.WRONG_OWNER));

        var pinned = (PinResult.Pinned) holds.validateAndPin(held.holdId().value(), "alice");
        assertThat(pinned.eventId()).isEqualTo(eventId);
        assertThat(pinned.section()).isEqualTo("B");
        assertThat(pinned.seatIds()).containsExactly(8L, 9L);
        assertThat(pinned.leaseExpiresAtMs() - held.expiresAtMs())
                .isBetween(Duration.ofSeconds(140).toMillis(), Duration.ofSeconds(151).toMillis());

        assertThat(holds.validateAndPin(HoldId.generate(eventId, "B").value(), "alice"))
                .isEqualTo(new PinResult.Rejected(PinResult.Reason.NOT_FOUND));
        assertThatThrownBy(() -> holds.validateAndPin("garbage", "alice"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sweeperReleasesLapsedHoldsButNeverAPinnedOne() throws Exception {
        HoldService holds = service(RedisTestSupport.props(Duration.ofMillis(300), Duration.ofSeconds(10),
                Duration.ofSeconds(1)));
        var lapsing = (HoldResult.Held) holds.hold("alice", eventId, "C", List.of(1L));
        var pinned = (HoldResult.Held) holds.hold("bob", eventId, "C", List.of(2L));
        holds.validateAndPin(pinned.holdId().value(), "bob");

        Thread.sleep(500);
        assertThat(holds.sweepExpired()).isGreaterThanOrEqualTo(1);

        assertThat(holds.validateAndPin(lapsing.holdId().value(), "alice"))
                .isEqualTo(new PinResult.Rejected(PinResult.Reason.NOT_FOUND));
        assertThat(holds.snapshot(eventId, "C").held()).containsExactly(2L);
        assertThat(published.events).filteredOn(e -> e.eventId() == eventId && e.type() == Type.SEAT_RELEASED)
                .singleElement().extracting(SeatEvent::seatId).isEqualTo(1L);
        assertThat(holds.hold("carol", eventId, "C", List.of(1L))).isInstanceOf(HoldResult.Held.class);
    }

    @Test
    void aLapsedButUnsweptSeatCanBeTakenAndTheSweeperLeavesTheNewHolderAlone() throws Exception {
        HoldService holds = service(RedisTestSupport.props(Duration.ofMillis(300), Duration.ofSeconds(10),
                Duration.ofSeconds(1)));
        var old = (HoldResult.Held) holds.hold("alice", eventId, "D", List.of(4L));
        Thread.sleep(500);

        HoldService longer = service();
        var fresh = (HoldResult.Held) longer.hold("bob", eventId, "D", List.of(4L));
        holds.sweepExpired();

        assertThat(holds.snapshot(eventId, "D").held()).containsExactly(4L);
        assertThat(longer.validateAndPin(fresh.holdId().value(), "bob")).isInstanceOf(PinResult.Pinned.class);
        assertThat(holds.validateAndPin(old.holdId().value(), "alice")).isInstanceOf(PinResult.Rejected.class);
        assertThat(published.events).noneMatch(e -> e.eventId() == eventId && e.type() == Type.SEAT_RELEASED);
    }

    @Test
    void lostSectionStateStartsANewEpochBeforeAcceptingHolds() {
        HoldService holds = service();
        var first = (HoldResult.Held) holds.hold("alice", eventId, "E", List.of(1L));
        // A failover that lost the section: every key under its hash tag is gone.
        var keys = new SectionKeys(eventId, "E");
        redis.del(redis.keys(keys.tag().replace("{", "\\{").replace("}", "\\}") + "*").toArray(String[]::new));

        var second = (HoldResult.Held) holds.hold("bob", eventId, "E", List.of(1L));
        assertThat(second.epoch()).isNotEqualTo(first.epoch());
        assertThat(published.events.getLast().seq()).isEqualTo(1);
        assertThat(holds.validateAndPin(first.holdId().value(), "alice"))
                .isEqualTo(new PinResult.Rejected(PinResult.Reason.NOT_FOUND));
    }

    @Test
    void perUserLimitIsCheckedBeforeHolding() {
        HoldService holds = service();
        assertThat(holds.hold("alice", eventId, "F", List.of(1L, 2L, 3L))).isInstanceOf(HoldResult.Held.class);
        assertThat(holds.hold("alice", eventId, "G", List.of(1L, 2L)))
                .isEqualTo(new HoldResult.Rejected(HoldResult.Reason.USER_LIMIT, null));
        assertThat(holds.hold("alice", eventId, "G", List.of(1L))).isInstanceOf(HoldResult.Held.class);
    }

    @Test
    void releasingFreesTheUserLimitAgain() {
        HoldService holds = service();
        var held = (HoldResult.Held) holds.hold("alice", eventId, "H", List.of(1L, 2L, 3L, 4L));
        assertThat(holds.release(held.holdId().value(), "bob")).isFalse();
        assertThat(holds.release(held.holdId().value(), "alice")).isTrue();
        assertThat(holds.hold("alice", eventId, "H", List.of(1L, 2L, 3L, 4L))).isInstanceOf(HoldResult.Held.class);
    }

    @Test
    void rejectsMalformedRequests() {
        HoldService holds = service();
        var bad = new ArrayList<Runnable>(List.of(
                () -> holds.hold("u", eventId, "A", List.of()),
                () -> holds.hold("u", eventId, "A", List.of(1L, 2L, 3L, 4L, 5L)),
                () -> holds.hold("u", eventId, "A", List.of(1L, 1L)),
                () -> holds.hold("u", eventId, "A:B", List.of(1L)),
                () -> holds.hold("", eventId, "A", List.of(1L))));
        bad.forEach(call -> assertThatThrownBy(call::run).isInstanceOf(IllegalArgumentException.class));
    }
}
