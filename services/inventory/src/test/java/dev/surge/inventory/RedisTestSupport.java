package dev.surge.inventory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.surge.contracts.events.SeatEvent;
import dev.surge.inventory.config.InventoryProperties;
import dev.surge.inventory.events.SeatEventPublisher;
import io.lettuce.core.RedisClient;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import org.testcontainers.containers.GenericContainer;

/** One Redis container for the whole test JVM. */
public final class RedisTestSupport {

    public static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    private RedisTestSupport() {}

    public static String node() {
        return REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
    }

    public static RedisClusterCommands<String, String> connect() {
        return RedisClient.create("redis://" + node()).connect().sync();
    }

    public static InventoryProperties props(Duration holdLease, Duration paymentTimeout, Duration pinGrace) {
        return new InventoryProperties(holdLease, paymentTimeout, pinGrace, 4, 4, Duration.ofHours(1), 200,
                List.of(node()), "", 0);
    }

    /** Captures published events in order. */
    public static final class RecordingPublisher implements SeatEventPublisher {
        public final List<SeatEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void publish(List<SeatEvent> batch) {
            events.addAll(batch);
        }
    }
}
