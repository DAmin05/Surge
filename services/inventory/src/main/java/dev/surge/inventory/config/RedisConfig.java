package dev.surge.inventory.config;

import java.time.Duration;
import java.util.List;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
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
        // Follow failovers quickly. Adaptive refresh (on MOVED/ASK/reconnects) is on by
        // default in Lettuce 7; add a periodic refresh for failovers nobody trips over.
        client.setOptions(ClusterClientOptions.builder()
                .topologyRefreshOptions(ClusterTopologyRefreshOptions.builder()
                        .enablePeriodicRefresh(Duration.ofSeconds(5))
                        .build())
                .build());
        return client.connect().sync();
    }
}
