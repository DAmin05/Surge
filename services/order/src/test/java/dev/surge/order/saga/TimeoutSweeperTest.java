package dev.surge.order.saga;

import static dev.surge.order.OrderFixtures.string;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.surge.order.OrderFixtures;
import dev.surge.order.PostgresTestSupport;
import dev.surge.order.claim.ClaimResult;
import dev.surge.order.payment.PaymentClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/** The sweeper asks Payment before cancelling anything. */
class TimeoutSweeperTest {

    /** Payment stand-in: status per key; a missing key is "unreachable". */
    static final class FakePayments extends PaymentClient {
        final Map<UUID, Optional<Status>> statuses = new ConcurrentHashMap<>();

        FakePayments() {
            super("http://unused");
        }

        @Override
        public Optional<Status> status(UUID key) {
            Optional<Status> s = statuses.get(key);
            if (s == null) {
                throw new ResourceAccessException("payment unreachable");
            }
            return s;
        }
    }

    private final FakePayments payments = new FakePayments();
    // T = 0: every PAYMENT_PENDING order is overdue.
    private final TimeoutSweeper sweeper = new TimeoutSweeper(OrderFixtures.ORDERS, OrderFixtures.outcomes(), payments,
            Duration.ZERO, new SimpleMeterRegistry());

    @Test
    void resolvesEachOverdueOrderFromPaymentsAnswer() throws Exception {
        var event = PostgresTestSupport.seedEvent(Map.of("A", 1000L), 5);
        var paid = claim(event, "u1", 0);
        var failed = claim(event, "u2", 1);
        var pending = claim(event, "u3", 2);
        var neverSeen = claim(event, "u4", 3);
        var unreachable = claim(event, "u5", 4);
        payments.statuses.put(paid.paymentKey(), Optional.of(PaymentClient.Status.CAPTURED));
        payments.statuses.put(failed.paymentKey(), Optional.of(PaymentClient.Status.FAILED));
        payments.statuses.put(pending.paymentKey(), Optional.of(PaymentClient.Status.PENDING));
        payments.statuses.put(neverSeen.paymentKey(), Optional.empty());
        Thread.sleep(1100);

        sweeper.sweep();

        // A lost webhook for a paid order must not cancel it: the sweeper confirms it.
        assertThat(state(paid)).isEqualTo("CONFIRMED");
        assertThat(state(failed)).isEqualTo("CANCELLED");
        assertThat(state(pending)).isEqualTo("CANCELLED");
        assertThat(state(neverSeen)).isEqualTo("CANCELLED");
        // No answer from Payment: leave it for the next run rather than guess.
        assertThat(state(unreachable)).isEqualTo("PAYMENT_PENDING");
    }

    private ClaimResult.Claimed claim(PostgresTestSupport.Seeded event, String user, int seat) {
        return (ClaimResult.Claimed) OrderFixtures.claims()
                .claim(user, event.eventId(), "A", event.seats("A").subList(seat, seat + 1), "h", c -> { });
    }

    private static String state(ClaimResult.Claimed o) {
        return string("SELECT state FROM orders WHERE id = ?", o.orderId());
    }
}
