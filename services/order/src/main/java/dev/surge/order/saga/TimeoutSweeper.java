package dev.surge.order.saga;

import java.time.Duration;
import java.util.UUID;

import dev.surge.order.payment.PaymentClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * Moves orders stuck in PAYMENT_PENDING past T to compensation, but asks Payment first:
 * a lost webhook plus a blind timeout would cancel an order that was actually paid.
 * If Payment can't answer, the order is left for the next run.
 */
@Component
public class TimeoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(TimeoutSweeper.class);

    private final OrderStore orders;
    private final PaymentOutcomes outcomes;
    private final PaymentClient payments;
    private final Duration paymentTimeout;
    private final MeterRegistry meters;

    public TimeoutSweeper(OrderStore orders, PaymentOutcomes outcomes, PaymentClient payments,
            @Value("${surge.order.payment-timeout}") Duration paymentTimeout, MeterRegistry meters) {
        this.orders = orders;
        this.outcomes = outcomes;
        this.payments = payments;
        this.paymentTimeout = paymentTimeout;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${surge.order.timeout-sweep-interval}")
    public void sweep() {
        try {
            for (UUID key : orders.pendingOlderThan(paymentTimeout.toSeconds(), 100)) {
                resolve(key);
            }
        } catch (RuntimeException e) {
            log.warn("timeout sweep failed: {}", e.toString());
        }
    }

    void resolve(UUID key) {
        try {
            switch (payments.status(key).orElse(PaymentClient.Status.PENDING)) {
                case CAPTURED -> outcomes.succeeded(key, "sweeper");
                case FAILED, REFUNDED -> outcomes.failed(key, "sweeper");
                case PENDING -> outcomes.timedOut(key);
            }
        } catch (RestClientException e) {
            meters.counter("timeout_sweeper_payment_unreachable_total").increment();
            log.warn("payment status for {} unavailable; retrying next sweep: {}", key, e.toString());
        }
    }
}
