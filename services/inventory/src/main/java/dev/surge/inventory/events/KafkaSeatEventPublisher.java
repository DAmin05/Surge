package dev.surge.inventory.events;

import java.util.List;
import java.util.Map;

import dev.surge.contracts.events.SeatEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

public class KafkaSeatEventPublisher implements SeatEventPublisher, AutoCloseable {

    public static final String TOPIC = "seat-events";
    private static final Logger log = LoggerFactory.getLogger(KafkaSeatEventPublisher.class);

    private final KafkaProducer<String, String> producer;
    private final JsonMapper json;
    private final Counter sent;
    private final Counter failed;

    public KafkaSeatEventPublisher(String bootstrap, JsonMapper json, MeterRegistry meters) {
        this.producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.LINGER_MS_CONFIG, 2,
                ProducerConfig.CLIENT_ID_CONFIG, "inventory"),
                new StringSerializer(), new StringSerializer());
        this.json = json;
        this.sent = meters.counter("seat_events_published_total", "result", "ok");
        this.failed = meters.counter("seat_events_published_total", "result", "error");
    }

    @Override
    public void publish(List<SeatEvent> events) {
        for (SeatEvent e : events) {
            producer.send(new ProducerRecord<>(TOPIC, e.partitionKey(), json.writeValueAsString(e)), (md, err) -> {
                if (err == null) {
                    sent.increment();
                } else {
                    failed.increment();
                    log.warn("seat event lost (clients will re-snapshot): {} seq={}", e.type(), e.seq(), err);
                }
            });
        }
    }

    @Override
    public void close() {
        producer.close();
    }
}
