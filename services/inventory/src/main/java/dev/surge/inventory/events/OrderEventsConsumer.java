package dev.surge.inventory.events;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import dev.surge.contracts.events.OrderEvent;
import dev.surge.inventory.config.InventoryProperties;
import dev.surge.inventory.hold.HoldService;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ends the Redis side of the saga once Postgres has decided:
 * <ul>
 *   <li>{@code ORDER_CONFIRMED}: seats become sold markers, the hold is deleted;</li>
 *   <li>{@code ORDER_CANCELLED}: the hold is released, so the map frees the seats.</li>
 * </ul>
 * At-least-once: offsets are committed only after a batch is fully applied, and both
 * actions are idempotent. A record that keeps failing is retried until it succeeds;
 * the saga never needs it for correctness, only for the map.
 */
@Component
public class OrderEventsConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OrderEventsConsumer.class);
    public static final String TOPIC = "order-events";

    private final InventoryProperties props;
    private final HoldService holds;
    private final JsonMapper json;
    private final MeterRegistry meters;
    private volatile KafkaConsumer<String, String> consumer;
    private volatile Thread thread;
    private volatile boolean running;

    public OrderEventsConsumer(InventoryProperties props, HoldService holds, JsonMapper json, MeterRegistry meters) {
        this.props = props;
        this.holds = holds;
        this.json = json;
        this.meters = meters;
    }

    @Override
    public void start() {
        if (props.kafkaBootstrap() == null || props.kafkaBootstrap().isBlank()) {
            log.warn("KAFKA_BOOTSTRAP not set: not consuming order events");
            return;
        }
        consumer = new KafkaConsumer<>(Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, props.kafkaBootstrap(),
                ConsumerConfig.GROUP_ID_CONFIG, "inventory",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 200),
                new StringDeserializer(), new StringDeserializer());
        running = true;
        thread = Thread.ofVirtual().name("order-events-consumer").start(this::loop);
    }

    private void loop() {
        try {
            consumer.subscribe(List.of(TOPIC));
            while (running) {
                var records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    applyWithRetry(record);
                }
                if (!records.isEmpty()) {
                    consumer.commitSync();
                }
            }
        } catch (WakeupException e) {
            // shutting down
        } finally {
            consumer.close();
        }
    }

    private void applyWithRetry(ConsumerRecord<String, String> record) {
        long backoffMs = 100;
        while (running) {
            try {
                apply(json.readValue(record.value(), OrderEvent.class));
                return;
            } catch (RuntimeException e) {
                meters.counter("order_events_apply_failures_total").increment();
                log.warn("order event at {}-{}@{} failed, retrying: {}", record.topic(), record.partition(),
                        record.offset(), e.toString());
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoffMs = Math.min(backoffMs * 2, 5_000);
            }
        }
    }

    void apply(OrderEvent e) {
        switch (e.type()) {
            case ORDER_CONFIRMED -> holds.markSold(e.orderId(), e.eventId(), e.section(), e.seatIds(), e.holdId());
            case ORDER_CANCELLED -> {
                if (e.holdId() != null) {
                    holds.release(e.holdId(), null);
                }
            }
            case PAYMENT_REQUESTED, REFUND_REQUESTED -> { }
        }
        meters.counter("order_events_applied_total", "type", e.type().name()).increment();
    }

    @Override
    public void stop() {
        running = false;
        if (consumer != null) {
            consumer.wakeup();
        }
        if (thread != null) {
            try {
                thread.join(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
