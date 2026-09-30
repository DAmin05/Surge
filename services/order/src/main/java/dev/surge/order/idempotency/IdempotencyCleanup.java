package dev.surge.order.idempotency;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keys are retained for 24 hours, like Stripe's. */
@Component
public class IdempotencyCleanup {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanup.class);

    private final IdempotencyStore store;

    public IdempotencyCleanup(IdempotencyStore store) {
        this.store = store;
    }

    @Scheduled(cron = "${surge.order.idempotency-cleanup-cron:0 17 3 * * *}")
    public void run() {
        log.info("deleted {} idempotency keys older than 24h", store.deleteOlderThan(Duration.ofHours(24)));
    }
}
