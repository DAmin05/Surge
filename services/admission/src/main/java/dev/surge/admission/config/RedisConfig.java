package dev.surge.admission.config;

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

/** Same shape as Inventory's (services don't share code): one node = standalone. */
@Configuration(proxyBeanMethods = false)
public class RedisConfig {

    @Bean(destroyMethod = "shutdown")
    ClientResources redisClientResources() {
        return DefaultClientResources.create();
    }

    @Bean
    RedisClusterCommands<String, String> redis(AdmissionProperties props, ClientResources resources) {
        List<RedisURI> uris = props.redisNodes().stream().map(n -> RedisURI.create("redis://" + n)).toList();
        if (uris.size() == 1) {
            return RedisClient.create(resources, uris.getFirst()).connect().sync();
        }
        var client = RedisClusterClient.create(resources, uris);
        client.setOptions(ClusterClientOptions.builder()
                .topologyRefreshOptions(ClusterTopologyRefreshOptions.builder()
                        .enablePeriodicRefresh(Duration.ofSeconds(5))
                        .build())
                .build());
        return client.connect().sync();
    }
}
