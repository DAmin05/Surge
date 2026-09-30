package dev.surge.order.claim;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import dev.surge.order.PostgresTestSupport;
import dev.surge.order.config.OrderProperties;
import dev.surge.order.outbox.Outbox;
import dev.surge.order.saga.OrderStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Week-1 exit criterion: 1,000 virtual threads race for one seat and exactly one wins.
 * Plus the properties that make that guarantee hold for real orders: multi-seat claims
 * are all-or-nothing and can't deadlock, and the per-user limit can't be raced.
 */
class ClaimRaceTest {

    private final JdbcClient jdbc = JdbcClient.create(PostgresTestSupport.APP);
    private final ClaimService claims = new ClaimService(
            jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(PostgresTestSupport.APP)),
            new OrderStore(jdbc),
            new Outbox(jdbc, JsonMapper.builder().build()),
            new OrderProperties(4, "unused:0", Duration.ofSeconds(1)),
            new SimpleMeterRegistry());

    /** Runs every task at once on virtual threads, released by a single latch. */
    private static <T> List<T> race(List<Callable<T>> tasks) throws Exception {
        var results = new ConcurrentLinkedQueue<T>();
        var errors = new ConcurrentLinkedQueue<Throwable>();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Callable<T> task : tasks) {
                pool.submit(() -> {
                    try {
                        start.await();
                        results.add(task.call());
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                });
            }
            start.countDown();
        }
        // A deadlock or serialization failure would surface here.
        assertThat(errors).isEmpty();
        return new ArrayList<>(results);
    }

    @Test
    void thousandVirtualThreadsRaceForOneSeatAndExactlyOneWins() throws Exception {
        var event = PostgresTestSupport.seedEvent(Map.of("A", 5000L), 1);
        long seat = event.seats("A").getFirst();

        var tasks = new ArrayList<Callable<ClaimResult>>();
        for (int i = 0; i < 1000; i++) {
            String user = "user-" + i;
            tasks.add(() -> claims.claim(user, event.eventId(), "A", List.of(seat)));
        }
        List<ClaimResult> results = race(tasks);

        assertThat(results).hasSize(1000);
        var winners = results.stream().filter(r -> r instanceof ClaimResult.Claimed)
                .map(ClaimResult.Claimed.class::cast).toList();
        assertThat(winners).hasSize(1);
        assertThat(results).filteredOn(r -> r.equals(new ClaimResult.Rejected(ClaimResult.Reason.SEAT_TAKEN)))
                .hasSize(999);

        long winner = winners.getFirst().orderId();
        assertThat(jdbc.sql("SELECT status || ':' || order_id FROM seats WHERE id = ?").param(seat)
                .query(String.class).single()).isEqualTo("RESERVED:" + winner);
        assertThat(count("SELECT count(*) FROM orders WHERE event_id = ?", event.eventId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM order_items WHERE seat_id = ?", seat)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox WHERE aggregate_id = ?", Long.toString(winner))).isEqualTo(1);
    }

    @Test
    void overlappingMultiSeatClaimsAreAllOrNothingAndNeverDeadlock() throws Exception {
        var event = PostgresTestSupport.seedEvent(Map.of("B", 2500L), 12);
        List<Long> seats = event.seats("B");
        var random = new Random(42);

        var tasks = new ArrayList<Callable<ClaimResult>>();
        for (int i = 0; i < 300; i++) {
            var pick = new ArrayList<>(seats);
            java.util.Collections.shuffle(pick, random);
            // Unsorted on purpose: the service must impose the lock order itself.
            List<Long> want = List.copyOf(pick.subList(0, 1 + random.nextInt(4)));
            String user = "buyer-" + i;
            tasks.add(() -> claims.claim(user, event.eventId(), "B", want));
        }
        List<ClaimResult> results = race(tasks);

        var claimed = results.stream().filter(r -> r instanceof ClaimResult.Claimed)
                .map(ClaimResult.Claimed.class::cast).toList();
        assertThat(claimed).isNotEmpty();
        assertThat(results).filteredOn(r -> r instanceof ClaimResult.Rejected rej
                && rej.reason() != ClaimResult.Reason.SEAT_TAKEN).isEmpty();

        // No seat in two winning orders.
        var seen = new HashSet<Long>();
        claimed.forEach(c -> c.seatIds().forEach(s -> assertThat(seen.add(s)).as("seat %s sold twice", s).isTrue()));

        // Each winner owns every one of its seats (all), and losers own none (nothing).
        for (var c : claimed) {
            assertThat(jdbc.sql("SELECT order_id FROM seats WHERE id = ANY(?)").param(c.seatIds().toArray(Long[]::new))
                    .query(Long.class).list()).containsOnly(c.orderId()).hasSize(c.seatIds().size());
            assertThat(c.amountCents()).isEqualTo(2500L * c.seatIds().size());
        }
        assertThat(count("SELECT count(*) FROM seats WHERE event_id = ? AND status <> 'AVAILABLE'", event.eventId()))
                .isEqualTo(seen.size());
        assertThat(count("SELECT count(*) FROM orders WHERE event_id = ?", event.eventId())).isEqualTo(claimed.size());
        // Invariant 7: the order total is the sum of its items.
        assertThat(count("""
                SELECT count(*) FROM orders o
                 WHERE o.event_id = ?
                   AND o.amount_cents <> (SELECT sum(unit_price_cents) FROM order_items WHERE order_id = o.id)""",
                event.eventId())).isZero();
    }

    @Test
    void concurrentCheckoutsCannotTogetherExceedTheUserLimit() throws Exception {
        var event = PostgresTestSupport.seedEvent(Map.of("C", 1000L), 40);
        List<Long> seats = event.seats("C");

        // One user, 20 concurrent 2-seat checkouts on disjoint seats: only 2 can fit in 4.
        var tasks = new ArrayList<Callable<ClaimResult>>();
        for (int i = 0; i < 20; i++) {
            List<Long> pair = List.of(seats.get(2 * i), seats.get(2 * i + 1));
            tasks.add(() -> claims.claim("greedy", event.eventId(), "C", pair));
        }
        List<ClaimResult> results = race(tasks);

        assertThat(results).filteredOn(r -> r instanceof ClaimResult.Claimed).hasSize(2);
        assertThat(results).filteredOn(r -> r.equals(new ClaimResult.Rejected(ClaimResult.Reason.USER_LIMIT)))
                .hasSize(18);
        assertThat(count("""
                SELECT count(*) FROM order_items oi JOIN orders o ON o.id = oi.order_id
                 WHERE o.event_id = ? AND o.user_id = 'greedy'""", event.eventId())).isEqualTo(4);
    }

    @Test
    void cancelledOrdersDoNotCountTowardTheUserLimit() {
        var event = PostgresTestSupport.seedEvent(Map.of("D", 1000L), 8);
        List<Long> seats = event.seats("D");
        var first = (ClaimResult.Claimed) claims.claim("u", event.eventId(), "D", seats.subList(0, 4));
        assertThat(claims.claim("u", event.eventId(), "D", seats.subList(4, 5)))
                .isEqualTo(new ClaimResult.Rejected(ClaimResult.Reason.USER_LIMIT));

        JdbcClient.create(PostgresTestSupport.OWNER)
                .sql("UPDATE orders SET state = 'CANCELLED' WHERE id = ?").param(first.orderId()).update();
        assertThat(claims.claim("u", event.eventId(), "D", seats.subList(4, 5)))
                .isInstanceOf(ClaimResult.Claimed.class);
    }

    @Test
    void seatsFromAnotherSectionOrEventAreRejectedAndNothingIsWritten() {
        var event = PostgresTestSupport.seedEvent(Map.of("E", 1000L, "F", 2000L), 2);
        long orders = count("SELECT count(*) FROM orders WHERE event_id = ?", event.eventId());

        var mixed = List.of(event.seats("E").getFirst(), event.seats("F").getFirst());
        assertThat(claims.claim("u", event.eventId(), "E", mixed))
                .isEqualTo(new ClaimResult.Rejected(ClaimResult.Reason.INVALID_SEATS));
        assertThat(claims.claim("u", event.eventId() + 1_000_000, "E", event.seats("E")))
                .isEqualTo(new ClaimResult.Rejected(ClaimResult.Reason.INVALID_SEATS));

        assertThat(count("SELECT count(*) FROM orders WHERE event_id = ?", event.eventId())).isEqualTo(orders);
        assertThat(count("SELECT count(*) FROM seats WHERE event_id = ? AND status <> 'AVAILABLE'", event.eventId()))
                .isZero();
    }

    private long count(String sql, Object param) {
        return jdbc.sql(sql).param(param).query(Long.class).single();
    }
}
