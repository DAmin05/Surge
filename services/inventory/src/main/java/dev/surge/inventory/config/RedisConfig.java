package dev.surge.inventory.config;

import java.time.Duration;
import java.util.List;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One multiplexed, thread-safe Lettuce connection. Code is written against
 * {@link RedisClusterCommands}, which a standalone connection also implements, so
 * tests can use a single Redis node.
 */
@Configuration(proxyBeanMethods = false)
public class RedisConfig {

    @Bean(destroyMethod = "shutdown")
    ClientResources redisClientResources() {
        return DefaultClientResources.create();
    }

    @Bean
    RedisClusterCommands<String, String> redis(InventoryProperties props, ClientResources resources) {
        List<RedisURI> uris = props.redisNodes().stream()
                .map(node -> RedisURI.create("redis://" + node))
                .toList();
        if (uris.size() == 1) {
            return RedisClient.create(resources, uris.getFirst()).connect().sync();
        }
        var client = RedisClusterClient.create(resources, uris);
        // Follow a failover within seconds. By default Lettuce queues commands for a dead
        // node while it reconnects with backoff, and only refreshes the topology after
        // several failed reconnects: about 30 s of stalled requests when a primary dies.
        // Instead: adaptive refresh (on by default) fires after 2 failed reconnects, reject
        // commands while disconnected (callers answer 503 RETRY_LATER and clients retry),
        // and time out a command after 2 s.
        client.setOptions(ClusterClientOptions.builder()
                .topologyRefreshOptions(ClusterTopologyRefreshOptions.builder()
                        .enablePeriodicRefresh(Duration.ofSeconds(5))
                        .refreshTriggersReconnectAttempts(2)
                        .adaptiveRefreshTriggersTimeout(Duration.ofSeconds(1))
                        .build())
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .timeoutOptions(TimeoutOptions.enabled(Duration.ofSeconds(2)))
                .build());
        return client.connect().sync();
    }
}
