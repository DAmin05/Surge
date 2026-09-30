package dev.surge.inventory.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.surge.contracts.events.OrderEvent;
import dev.surge.inventory.RedisTestSupport;
import dev.surge.inventory.RedisTestSupport.RecordingPublisher;
import dev.surge.inventory.config.InventoryProperties;
import dev.surge.inventory.hold.EpochRebuilder;
import dev.surge.inventory.hold.HoldResult;
import dev.surge.inventory.hold.HoldService;
import dev.surge.inventory.redis.LuaScripts;
import dev.surge.inventory.redis.SectionKeys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.redpanda.RedpandaContainer;
import tools.jackson.databind.json.JsonMapper;

/** The saga's Redis tail against a real broker: order events in, sold ledger out and back. */
class OrderEventsKafkaTest {

    static final RedpandaContainer REDPANDA = new RedpandaContainer("redpandadata/redpanda:v25.2.1");

    static {
        REDPANDA.start();
    }

    private final JsonMapper json = JsonMapper.builder().build();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RecordingPublisher published = new RecordingPublisher();

    @Test
    void confirmedOrdersBecomeSoldAndSurviveLosingRedis() throws Exception {
        var redis = RedisTestSupport.connect();
        var props = new InventoryProperties(Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofSeconds(30),
                4, 4, Duration.ofHours(1), 200, List.of(RedisTestSupport.node()), REDPANDA.getBootstrapServers(), 0);
        var ledger = new KafkaSoldLedger(REDPANDA.getBootstrapServers(), json);
        var holds = new HoldService(redis, new LuaScripts(redis), new EpochRebuilder(redis, ledger, meters),
                published, ledger, props, meters);
        var consumer = new OrderEventsConsumer(props, holds, json, meters);

        long eventId = 9_000_001;
        var sold = (HoldResult.Held) holds.hold("alice", eventId, "K", List.of(1L, 2L));
        var cancelled = (HoldResult.Held) holds.hold("bob", eventId, "K", List.of(3L));

        consumer.start();
        try (var producer = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers()),
                new StringSerializer(), new StringSerializer())) {
            // Each twice: delivery is at-least-once.
            for (int i = 0; i < 2; i++) {
                producer.send(new ProducerRecord<>(OrderEventsConsumer.TOPIC, "1", json.writeValueAsString(
                        OrderEvent.confirmed(1, "alice", eventId, "K", List.of(1L, 2L), sold.holdId().value()))));
                producer.send(new ProducerRecord<>(OrderEventsConsumer.TOPIC, "2", json.writeValueAsString(
                        OrderEvent.cancelled(2, "bob", eventId, "K", List.of(3L), cancelled.holdId().value(), "t"))));
            }
        }

        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline
                && !(holds.snapshot(eventId, "K").sold().size() == 2 && holds.snapshot(eventId, "K").held().isEmpty())) {
            Thread.sleep(100);
        }
        consumer.stop();

        var snap = holds.snapshot(eventId, "K");
        assertThat(snap.sold()).containsExactly(1L, 2L);
        assertThat(snap.held()).isEmpty();
        assertThat(published.events).filteredOn(e -> e.type() == dev.surge.contracts.events.SeatEvent.Type.SEAT_SOLD)
                .hasSize(2);

        // Lose the section in Redis; the compacted topic brings the sold seats back.
        redis.del(redis.keys(new SectionKeys(eventId, "K").tag() + "*").toArray(String[]::new));
        var restored = new CopyOnWriteArrayList<Long>();
        new KafkaSoldLedger(REDPANDA.getBootstrapServers(), json).replay(eventId, "K", restored::add);
        assertThat(restored).contains(1L, 2L).doesNotContain(3L);
        assertThat(holds.hold("carol", eventId, "K", List.of(2L)))
                .isEqualTo(new HoldResult.Rejected(HoldResult.Reason.SEAT_SOLD, 2L));
        assertThat(holds.hold("carol", eventId, "K", List.of(3L))).isInstanceOf(HoldResult.Held.class);
        ledger.close();
    }
}
