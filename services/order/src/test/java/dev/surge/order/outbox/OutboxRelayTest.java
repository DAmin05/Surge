package dev.surge.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.Executors;

import dev.surge.order.KafkaTestSupport;
import dev.surge.order.OrderFixtures;
import dev.surge.order.PostgresTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class OutboxRelayTest {

    @Test
    void twoRelaysDrainTheOutboxWithoutPublishingAnyRowTwice() throws Exception {
        String topic = "relay-test-" + UUID.randomUUID();
        var owner = JdbcClient.create(PostgresTestSupport.OWNER);
        // Anything left over by other tests is published too; count only our rows.
        String marker = UUID.randomUUID().toString();
        for (int i = 0; i < 500; i++) {
            owner.sql("INSERT INTO outbox (aggregate_id, topic, payload) VALUES (?, ?, ?::jsonb)")
                    .params("agg-" + (i % 37), topic, "{\"marker\":\"" + marker + "\",\"n\":" + i + "}")
                    .update();
        }

        try (var a = relay(); var b = relay(); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 20; i++) {
                pool.submit(a::relay);
                pool.submit(b::relay);
            }
        }

        assertThat(OrderFixtures.count("SELECT count(*) FROM outbox WHERE topic = ? AND published_at IS NULL", topic))
                .isZero();
        var records = KafkaTestSupport.readUntil(topic, Duration.ofSeconds(20), seen -> seen.size() >= 500);
        assertThat(records).hasSize(500);
        var numbers = new HashSet<String>();
        records.forEach(r -> assertThat(numbers.add(r.value())).as("published twice: %s", r.value()).isTrue());
        // Keyed by aggregate, so one aggregate's events share a partition.
        assertThat(records).allMatch(r -> r.key().startsWith("agg-"));
    }

    @Test
    void lagGaugeReadsZeroWhenTheOutboxIsDrainedNotNaN() {
        var meters = new SimpleMeterRegistry();
        try (var relay = new OutboxRelay(OrderFixtures.JDBC, OrderFixtures.TX, meters, KafkaTestSupport.bootstrap(), 50)) {
            relay.relay();
            assertThat(meters.get("outbox_oldest_unpublished_seconds").gauge().value()).isZero();
        }
    }

    private static OutboxRelay relay() {
        return new OutboxRelay(OrderFixtures.JDBC, OrderFixtures.TX, new SimpleMeterRegistry(),
                KafkaTestSupport.bootstrap(), 50);
    }
}
