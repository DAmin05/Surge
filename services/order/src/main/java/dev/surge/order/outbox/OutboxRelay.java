package dev.surge.order.outbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes outbox rows to Kafka. Each batch is claimed with
 * {@code FOR UPDATE SKIP LOCKED}, so any number of relay instances can run without
 * publishing the same row concurrently; rows are marked published only after the broker
 * acknowledged them. A crash between ack and commit republishes the batch: delivery is
 * at-least-once and consumers are idempotent.
 */
@Component
public final class OutboxRelay implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private record Row(long id, String aggregateId, String topic, String payload) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final KafkaProducer<String, String> producer;
    private final int batchSize;

    public OutboxRelay(JdbcClient jdbc, TransactionTemplate tx, MeterRegistry meters,
            @Value("${surge.order.kafka-bootstrap}") String bootstrap,
            @Value("${surge.order.outbox-batch:200}") int batchSize) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.batchSize = batchSize;
        this.producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.LINGER_MS_CONFIG, 1,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000,
                ProducerConfig.CLIENT_ID_CONFIG, "order-outbox"),
                new StringSerializer(), new StringSerializer());
        // How far behind the relay is: the age of the oldest unpublished event.
        Gauge.builder("outbox_oldest_unpublished_seconds", this, OutboxRelay::lagSeconds).register(meters);
    }

    @Scheduled(fixedDelayString = "${surge.order.outbox-poll-interval}")
    public void relay() {
        try {
            // Drain: keep going while full batches come back.
            while (publishBatch() == batchSize) {
                // next batch
            }
        } catch (RuntimeException e) {
            log.warn("outbox relay failed; batch will be retried: {}", e.toString());
        }
    }

    /** @return rows published */
    int publishBatch() {
        Integer n = tx.execute(status -> {
            List<Row> rows = jdbc.sql("""
                    SELECT id, aggregate_id, topic, payload::text FROM outbox
                     WHERE published_at IS NULL
                     ORDER BY id
                     LIMIT ?
                       FOR UPDATE SKIP LOCKED""")
                    .param(batchSize)
                    .query((rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                    .list();
            if (rows.isEmpty()) {
                return 0;
            }
            var acks = new ArrayList<Future<RecordMetadata>>(rows.size());
            for (Row row : rows) {
                acks.add(producer.send(new ProducerRecord<>(row.topic(), row.aggregateId(), row.payload())));
            }
            for (Future<RecordMetadata> ack : acks) {
                awaitAck(ack);
            }
            jdbc.sql("UPDATE outbox SET published_at = now() WHERE id = ANY(?)")
                    .param(rows.stream().map(Row::id).toArray(Long[]::new))
                    .update();
            return rows.size();
        });
        return n == null ? 0 : n;
    }

    private static void awaitAck(Future<RecordMetadata> ack) {
        try {
            ack.get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("broker did not acknowledge", e);
        }
    }

    private double lagSeconds() {
        try {
            return jdbc.sql("""
                    SELECT coalesce(extract(epoch FROM now() - min(created_at)), 0)::float8
                      FROM outbox WHERE published_at IS NULL""")
                    .query(Double.class).single();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    @Override
    public void close() {
        producer.close();
    }
}
