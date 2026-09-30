package dev.surge.order.checkout;

import static dev.surge.order.OrderFixtures.count;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import dev.surge.order.OrderFixtures;
import dev.surge.order.PostgresTestSupport;
import dev.surge.order.idempotency.IdempotencyStore;
import dev.surge.order.inventory.InventoryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Week-2 exit criterion, first half: retrying the same checkout 100 times yields one
 * order, whether the retries overlap or come one after another.
 */
class IdempotentCheckoutTest {

    private final FakeInventory inventory = new FakeInventory();
    private PostgresTestSupport.Seeded event;

    @BeforeEach
    void seed() {
        event = PostgresTestSupport.seedEvent(Map.of("A", 3000L), 4);
    }

    private CheckoutService checkout(Duration staleAfter) {
        return new CheckoutService(inventory, OrderFixtures.claims(),
                new IdempotencyStore(OrderFixtures.JDBC, staleAfter), OrderFixtures.JSON);
    }

    private static tools.jackson.databind.JsonNode json(String body) {
        return OrderFixtures.JSON.readTree(body);
    }

    private String hold(String user, List<Long> seats) {
        String id = "hold-" + UUID.randomUUID();
        inventory.holds.put(id, new FakeInventory.Hold(user, event.eventId(), "A", seats));
        return id;
    }

    @Test
    void hundredConcurrentRetriesOfOneCheckoutCreateOneOrder() throws Exception {
        CheckoutService service = checkout(Duration.ofMinutes(1));
        String hold = hold("alice", event.seats("A").subList(0, 2));
        String key = UUID.randomUUID().toString();

        var replies = new ConcurrentLinkedQueue<CheckoutService.Reply>();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 100; i++) {
                pool.submit(() -> {
                    start.await();
                    replies.add(service.checkout("alice", key, hold));
                    return null;
                });
            }
            start.countDown();
        }

        assertThat(replies).hasSize(100);
        var created = replies.stream().filter(r -> r.status() == 201).toList();
        // Overlapping retries either see the stored 201 or are told the first is in flight.
        assertThat(replies).allMatch(r -> r.status() == 201 || (r.status() == 409
                && r.body().contains("REQUEST_IN_PROGRESS")));
        assertThat(created).isNotEmpty();
        assertThat(created).extracting(r -> json(r.body())).containsOnly(json(created.getFirst().body()));
        assertThat(count("SELECT count(*) FROM orders WHERE event_id = ?", event.eventId())).isEqualTo(1);

        // Once settled, every retry replays the same response.
        for (int i = 0; i < 100; i++) {
            var again = service.checkout("alice", key, hold);
            assertThat(again.status()).isEqualTo(201);
            assertThat(again.replayed()).isTrue();
            // Replays come from jsonb: same document, normalized formatting.
            assertThat(json(again.body())).isEqualTo(json(created.getFirst().body()));
        }
        assertThat(count("SELECT count(*) FROM orders WHERE event_id = ?", event.eventId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox o JOIN orders r ON o.aggregate_id = r.id::text "
                + "WHERE r.event_id = ?", event.eventId())).isEqualTo(1);
    }

    @Test
    void failedCheckoutsAreReplayedToo() {
        CheckoutService service = checkout(Duration.ofMinutes(1));
        String key = UUID.randomUUID().toString();

        var first = service.checkout("alice", key, "no-such-hold");
        assertThat(first.status()).isEqualTo(410);
        inventory.holds.put("no-such-hold", new FakeInventory.Hold("alice", event.eventId(), "A",
                List.of(event.seats("A").get(3))));
        // The hold exists now, but the key already has an answer.
        var retry = service.checkout("alice", key, "no-such-hold");
        assertThat(retry.status()).isEqualTo(410);
        assertThat(retry.replayed()).isTrue();
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejected() {
        CheckoutService service = checkout(Duration.ofMinutes(1));
        String key = UUID.randomUUID().toString();
        assertThat(service.checkout("alice", key, hold("alice", event.seats("A").subList(0, 1))).status())
                .isEqualTo(201);

        var reused = service.checkout("alice", key, hold("alice", event.seats("A").subList(1, 2)));
        assertThat(reused.status()).isEqualTo(422);
        assertThat(reused.body()).contains("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void keysAreScopedPerUser() {
        CheckoutService service = checkout(Duration.ofMinutes(1));
        String key = "same-key-" + UUID.randomUUID();
        assertThat(service.checkout("alice", key, hold("alice", event.seats("A").subList(0, 1))).status())
                .isEqualTo(201);
        assertThat(service.checkout("bob", key, hold("bob", event.seats("A").subList(1, 2))).status())
                .isEqualTo(201);
    }

    @Test
    void retryLaterResponsesAreNotStoredSoTheRetryCanProceed() {
        CheckoutService service = checkout(Duration.ofMinutes(1));
        String key = UUID.randomUUID().toString();
        String hold = hold("alice", event.seats("A").subList(0, 1));

        inventory.forcedRejection = InventoryClient.PinOutcome.Reason.REBUILDING;
        assertThat(service.checkout("alice", key, hold).status()).isEqualTo(503);
        inventory.forcedRejection = null;
        assertThat(service.checkout("alice", key, hold).status()).isEqualTo(201);
    }

    @Test
    void aStuckKeyIsTakenOverAndTheStuckRequestCannotCommit() {
        String key = UUID.randomUUID().toString();
        var store = new IdempotencyStore(OrderFixtures.JDBC, Duration.ZERO);
        String hash = CheckoutService.requestHash("h");

        var stuck = (IdempotencyStore.Begin.Started) store.begin("carol", key, hash);
        // A retry after the stale window takes the key over with a new lease.
        var retry = store.begin("carol", key, hash);
        assertThat(retry).isInstanceOf(IdempotencyStore.Begin.Started.class);
        assertThat(((IdempotencyStore.Begin.Started) retry).lease()).isNotEqualTo(stuck.lease());

        // The stuck request wakes up: its completion must fail, so its transaction rolls back.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> store.complete("carol", key, stuck.lease(), 201, "{}"))
                .isInstanceOf(IdempotencyStore.LeaseLost.class);
        store.complete("carol", key, ((IdempotencyStore.Begin.Started) retry).lease(), 201, "{\"ok\":true}");
        assertThat(store.begin("carol", key, hash)).isEqualTo(new IdempotencyStore.Begin.Replay(201, "{\"ok\": true}"));
    }

    @Test
    void aClaimWhoseLeaseWasLostRollsBackEntirely() {
        // Stale window zero: the second request takes over while the first is mid-claim.
        String key = UUID.randomUUID().toString();
        var store = new IdempotencyStore(OrderFixtures.JDBC, Duration.ZERO);
        String hold = hold("dave", event.seats("A").subList(2, 3));
        var first = (IdempotencyStore.Begin.Started) store.begin("dave", key, CheckoutService.requestHash(hold));
        store.begin("dave", key, CheckoutService.requestHash(hold)); // takeover

        var claims = OrderFixtures.claims();
        var pinned = (InventoryClient.PinOutcome.Pinned) inventory.validateAndPin(hold, "dave");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> claims.claim("dave", pinned.eventId(),
                pinned.section(), pinned.seatIds(), hold,
                c -> store.complete("dave", key, first.lease(), 201, "{}")))
                .isInstanceOf(IdempotencyStore.LeaseLost.class);

        assertThat(count("SELECT count(*) FROM orders WHERE event_id = ? AND user_id = 'dave'", event.eventId()))
                .isZero();
        assertThat(count("SELECT count(*) FROM seats WHERE id = ? AND status = 'AVAILABLE'",
                event.seats("A").get(2))).isEqualTo(1);
    }

    @Test
    void cleanupDeletesKeysOlderThanADay() {
        var store = new IdempotencyStore(OrderFixtures.JDBC, Duration.ofMinutes(1));
        String key = "old-" + UUID.randomUUID();
        store.begin("erin", key, "h");
        JdbcOwner.ageKey("erin", key, Duration.ofHours(25));
        assertThat(store.deleteOlderThan(Duration.ofHours(24))).isGreaterThanOrEqualTo(1);
        assertThat(store.begin("erin", key, "different")).isInstanceOf(IdempotencyStore.Begin.Started.class);
    }

    /** Test-only writes that the app role may not need. */
    static final class JdbcOwner {
        static void ageKey(String user, String key, Duration age) {
            org.springframework.jdbc.core.simple.JdbcClient.create(PostgresTestSupport.OWNER)
                    .sql("UPDATE idempotency_keys SET created_at = now() - make_interval(secs => ?) "
                            + "WHERE user_id = ? AND key = ?")
                    .params(age.toSeconds(), user, key).update();
        }
    }
}
