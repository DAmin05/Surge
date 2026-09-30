package dev.surge.order.saga;

import static dev.surge.order.OrderFixtures.count;
import static dev.surge.order.OrderFixtures.string;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import dev.surge.order.OrderFixtures;
import dev.surge.order.PostgresTestSupport;
import dev.surge.order.claim.ClaimResult;
import dev.surge.order.saga.PaymentOutcomes.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Every way a payment result can arrive: once, twice, late, reordered, racing. */
class PaymentOutcomesTest {

    private final PaymentOutcomes outcomes = OrderFixtures.outcomes();
    private PostgresTestSupport.Seeded event;

    @BeforeEach
    void seed() {
        event = PostgresTestSupport.seedEvent(Map.of("A", 2000L), 8);
    }

    private ClaimResult.Claimed order(String user, List<Long> seats) {
        return (ClaimResult.Claimed) OrderFixtures.claims()
                .claim(user, event.eventId(), "A", seats, "hold-" + user, c -> { });
    }

    private String state(long orderId) {
        return string("SELECT state FROM orders WHERE id = ?", orderId);
    }

    private List<String> outboxTypes(long orderId) {
        return OrderFixtures.JDBC.sql("SELECT payload->>'type' FROM outbox WHERE aggregate_id = ? ORDER BY id")
                .param(Long.toString(orderId)).query(String.class).list();
    }

    @Test
    void checkoutWalksTheStateMachineAndRecordsEveryStep() {
        var o = order("alice", event.seats("A").subList(0, 2));
        assertThat(state(o.orderId())).isEqualTo("PAYMENT_PENDING");
        assertThat(OrderFixtures.JDBC.sql("SELECT coalesce(from_state, '-') || '>' || to_state FROM order_transitions "
                + "WHERE order_id = ? ORDER BY id").param(o.orderId()).query(String.class).list())
                .containsExactly("->CREATED", "CREATED>SEAT_RESERVED", "SEAT_RESERVED>PAYMENT_PENDING");
    }

    @Test
    void successConfirmsIssuesTicketsAndSellsTheSeats() {
        var o = order("alice", event.seats("A").subList(0, 2));

        assertThat(outcomes.succeeded(o.paymentKey(), "webhook")).isEqualTo(Result.CONFIRMED);

        assertThat(state(o.orderId())).isEqualTo("CONFIRMED");
        assertThat(count("SELECT count(*) FROM tickets WHERE order_id = ?", o.orderId())).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM seats WHERE order_id = ? AND status = 'SOLD'", o.orderId())).isEqualTo(2);
        assertThat(outboxTypes(o.orderId())).containsExactly("PAYMENT_REQUESTED", "ORDER_CONFIRMED");
    }

    @Test
    void duplicateSuccessAndStaleFailureAfterConfirmationChangeNothing() {
        var o = order("alice", event.seats("A").subList(0, 1));
        outcomes.succeeded(o.paymentKey(), "webhook");

        assertThat(outcomes.succeeded(o.paymentKey(), "webhook")).isEqualTo(Result.DUPLICATE);
        assertThat(outcomes.failed(o.paymentKey(), "webhook")).isEqualTo(Result.IGNORED_LATE_FAILURE);

        assertThat(state(o.orderId())).isEqualTo("CONFIRMED");
        assertThat(count("SELECT count(*) FROM tickets WHERE order_id = ?", o.orderId())).isEqualTo(1);
        assertThat(outboxTypes(o.orderId())).containsExactly("PAYMENT_REQUESTED", "ORDER_CONFIRMED");
    }

    @Test
    void failureCompensatesAndPutsTheSeatsBackOnSale() {
        List<Long> seats = event.seats("A").subList(2, 4);
        var o = order("bob", seats);

        assertThat(outcomes.failed(o.paymentKey(), "webhook")).isEqualTo(Result.CANCELLED);

        assertThat(state(o.orderId())).isEqualTo("CANCELLED");
        assertThat(count("SELECT count(*) FROM seats WHERE id = ANY(?) AND status = 'AVAILABLE' AND order_id IS NULL",
                (Object) seats.toArray(Long[]::new))).isEqualTo(2);
        assertThat(outboxTypes(o.orderId())).containsExactly("PAYMENT_REQUESTED", "ORDER_CANCELLED");
        assertThat(OrderFixtures.JDBC.sql("SELECT to_state FROM order_transitions WHERE order_id = ? ORDER BY id")
                .param(o.orderId()).query(String.class).list())
                .endsWith("PAYMENT_FAILED", "SEAT_RELEASED", "CANCELLED");
        // A cancelled order no longer counts toward bob's limit, and the seats sell again.
        assertThat(OrderFixtures.claims().claim("bob", event.eventId(), "A", seats))
                .isInstanceOf(ClaimResult.Claimed.class);
    }

    @Test
    void lateSuccessAfterReleaseAndResaleIsRefundedExactlyOnce() {
        // A's seat is released after a timeout, B buys it, then A's payment succeeds late.
        List<Long> seat = event.seats("A").subList(4, 5);
        var a = order("alice", seat);
        assertThat(outcomes.timedOut(a.paymentKey())).isEqualTo(Result.CANCELLED);
        var b = order("bob", seat);
        outcomes.succeeded(b.paymentKey(), "webhook");

        assertThat(outcomes.succeeded(a.paymentKey(), "webhook")).isEqualTo(Result.REFUND_REQUESTED);
        assertThat(outcomes.succeeded(a.paymentKey(), "webhook")).isEqualTo(Result.DUPLICATE);
        assertThat(outcomes.succeeded(a.paymentKey(), "sweeper")).isEqualTo(Result.DUPLICATE);

        assertThat(state(a.orderId())).isEqualTo("CANCELLED");
        assertThat(outboxTypes(a.orderId())).containsExactly("PAYMENT_REQUESTED", "ORDER_CANCELLED", "REFUND_REQUESTED");
        assertThat(string("SELECT order_id::text FROM tickets WHERE seat_id = ?", seat.getFirst()))
                .isEqualTo(Long.toString(b.orderId()));
    }

    @Test
    void unknownPaymentKeysAreReported() {
        assertThat(outcomes.succeeded(UUID.randomUUID(), "webhook")).isEqualTo(Result.UNKNOWN_PAYMENT);
        assertThat(outcomes.failed(UUID.randomUUID(), "webhook")).isEqualTo(Result.UNKNOWN_PAYMENT);
    }

    @Test
    void aStormOfDuplicateAndContradictoryCallbacksEndsInExactlyOneTerminalOutcome() throws Exception {
        var o = order("carol", event.seats("A").subList(5, 7));

        var tasks = new ArrayList<Callable<Result>>();
        for (int i = 0; i < 30; i++) {
            tasks.add(() -> outcomes.succeeded(o.paymentKey(), "webhook"));
            tasks.add(() -> outcomes.failed(o.paymentKey(), "webhook"));
            tasks.add(() -> outcomes.timedOut(o.paymentKey()));
        }
        var results = new ConcurrentLinkedQueue<Result>();
        var errors = new ConcurrentLinkedQueue<Throwable>();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var t : tasks) {
                pool.submit(() -> {
                    try {
                        start.await();
                        results.add(t.call());
                    } catch (Throwable e) {
                        errors.add(e);
                    }
                });
            }
            start.countDown();
        }

        assertThat(errors).isEmpty();
        // Exactly one callback decided the order; everything else saw a settled order.
        assertThat(results.stream().filter(r -> r == Result.CONFIRMED || r == Result.CANCELLED)).hasSize(1);
        String finalState = state(o.orderId());
        assertThat(finalState).isIn("CONFIRMED", "CANCELLED");
        if (finalState.equals("CONFIRMED")) {
            assertThat(count("SELECT count(*) FROM tickets WHERE order_id = ?", o.orderId())).isEqualTo(2);
            assertThat(results).doesNotContain(Result.REFUND_REQUESTED);
        } else {
            assertThat(count("SELECT count(*) FROM tickets WHERE order_id = ?", o.orderId())).isZero();
            // Success arriving after the cancellation must have asked for one refund.
            assertThat(results.stream().filter(r -> r == Result.REFUND_REQUESTED)).hasSize(1);
        }
    }
}
