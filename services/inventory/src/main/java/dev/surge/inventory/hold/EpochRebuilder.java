package dev.surge.inventory.hold;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import dev.surge.inventory.events.SoldLedger;
import dev.surge.inventory.redis.SectionKeys;
import io.lettuce.core.SetArgs;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A missing epoch means the section's Redis state is new or was lost (failover). The
 * hold script refuses holds until an epoch exists, so "no holds until the rebuild
 * finishes" is enforced atomically in Redis rather than by convention.
 *
 * <p>Rebuild: replay the compacted {@code seat-status} topic into the sold set, then
 * write a new random epoch. Several instances may rebuild at once; the replay is
 * idempotent and {@code SET NX} lets exactly one epoch win.
 */
@Component
public class EpochRebuilder {

    private static final Logger log = LoggerFactory.getLogger(EpochRebuilder.class);

    private final RedisClusterCommands<String, String> redis;
    private final SoldLedger ledger;
    private final MeterRegistry meters;
    private final Map<String, ReentrantLock> inFlight = new ConcurrentHashMap<>();

    public EpochRebuilder(RedisClusterCommands<String, String> redis, SoldLedger ledger, MeterRegistry meters) {
        this.redis = redis;
        this.ledger = ledger;
        this.meters = meters;
    }

    public void rebuild(SectionKeys section) {
        // Collapse concurrent rebuilds of one section in this instance into one.
        ReentrantLock lock = inFlight.computeIfAbsent(section.registryEntry(), k -> new ReentrantLock());
        lock.lock();
        try {
            if (redis.exists(section.epoch()) == 1) {
                return;
            }
            var restored = new AtomicInteger();
            ledger.replay(section.eventId(), section.section(), seat -> {
                redis.sadd(section.sold(), Long.toString(seat));
                restored.incrementAndGet();
            });
            String epoch = UUID.randomUUID().toString();
            if ("OK".equals(redis.set(section.epoch(), epoch, SetArgs.Builder.nx()))) {
                meters.counter("section_rebuilds_total").increment();
                log.info("section {} rebuilt: {} sold seats restored, epoch {}", section.registryEntry(),
                        restored.get(), epoch);
            }
        } finally {
            lock.unlock();
        }
    }
}
