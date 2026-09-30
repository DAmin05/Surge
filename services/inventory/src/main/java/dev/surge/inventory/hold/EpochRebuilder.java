package dev.surge.inventory.hold;

import java.util.UUID;

import dev.surge.inventory.redis.SectionKeys;
import io.lettuce.core.SetArgs;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A missing epoch means the section's Redis state is new or was lost (failover). The
 * hold script refuses holds until an epoch exists, so "no holds until the rebuild
 * finishes" is enforced atomically in Redis rather than by convention.
 *
 * <p>Rebuild = restore the sold set from the compacted {@code seat-status} topic, then
 * write a new random epoch. Sold markers are written once orders confirm (the saga);
 * until then there is nothing to replay and a rebuild only starts the new epoch.
 */
@Component
public class EpochRebuilder {

    private static final Logger log = LoggerFactory.getLogger(EpochRebuilder.class);

    private final RedisClusterCommands<String, String> redis;

    public EpochRebuilder(RedisClusterCommands<String, String> redis) {
        this.redis = redis;
    }

    public void rebuild(SectionKeys section) {
        String epoch = UUID.randomUUID().toString();
        // NX: if several instances race to rebuild, exactly one epoch wins.
        if ("OK".equals(redis.set(section.epoch(), epoch, SetArgs.Builder.nx()))) {
            log.info("section {} started epoch {}", section.registryEntry(), epoch);
        }
    }
}
