package dev.surge.order;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.testcontainers.redpanda.RedpandaContainer;

/** One Redpanda for the whole test JVM. */
public final class KafkaTestSupport {

    public static final RedpandaContainer REDPANDA = new RedpandaContainer("redpandadata/redpanda:v25.2.1");

    static {
        REDPANDA.start();
    }

    private KafkaTestSupport() {}

    public static String bootstrap() {
        return REDPANDA.getBootstrapServers();
    }

    /** Reads {@code topic} from the beginning until {@code done} holds or the timeout passes. */
    public static List<ConsumerRecord<String, String>> readUntil(String topic, Duration timeout,
            Predicate<List<ConsumerRecord<String, String>>> done) {
        var seen = new ArrayList<ConsumerRecord<String, String>>();
        try (var consumer = new KafkaConsumer<>(Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!done.test(seen) && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(seen::add);
            }
        }
        return seen;
    }
}
