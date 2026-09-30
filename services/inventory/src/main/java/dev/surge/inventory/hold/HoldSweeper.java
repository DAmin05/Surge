package dev.surge.inventory.hold;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Releases lapsed holds. Uses the per-section expiry sorted set rather than keyspace
 * notifications, which are fire-and-forget and lost on disconnect or failover. Every
 * instance runs it; the release script re-checks the lease, so concurrent sweeps and a
 * pin racing a sweep are both safe.
 */
@Component
public class HoldSweeper {

    private static final Logger log = LoggerFactory.getLogger(HoldSweeper.class);

    private final HoldService holds;

    public HoldSweeper(HoldService holds) {
        this.holds = holds;
    }

    @Scheduled(fixedDelayString = "${surge.inventory.sweep-interval}")
    public void sweep() {
        try {
            holds.sweepExpired();
        } catch (RuntimeException e) {
            // Redis failover in progress, typically. The next run picks it up.
            log.warn("sweep failed: {}", e.toString());
        }
    }
}
