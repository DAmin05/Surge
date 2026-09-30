package dev.surge.inventory.events;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import dev.surge.contracts.events.SeatStatus;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import tools.jackson.databind.json.JsonMapper;

public final class KafkaSoldLedger implements SoldLedger, AutoCloseable {

    public static final String TOPIC = "seat-status";

    private final String bootstrap;
    private final JsonMapper json;
    private final KafkaProducer<String, String> producer;

    public KafkaSoldLedger(String bootstrap, JsonMapper json) {
        this.bootstrap = bootstrap;
        this.json = json;
        this.producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.CLIENT_ID_CONFIG, "inventory-sold-ledger"),
                new StringSerializer(), new StringSerializer());
    }

    @Override
    public void record(List<SeatStatus> sold) {
        var acks = new ArrayList<Future<RecordMetadata>>();
        for (SeatStatus s : sold) {
            acks.add(producer.send(new ProducerRecord<>(TOPIC, Long.toString(s.seatId()), json.writeValueAsString(s))));
        }
        for (var ack : acks) {
            try {
                ack.get(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (ExecutionException | TimeoutException e) {
                throw new IllegalStateException("seat-status not acknowledged", e);
            }
        }
    }

    @Override
    public void replay(long eventId, String section, Consumer<Long> sold) {
        try (var consumer = new KafkaConsumer<>(Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG, "inventory-replay-" + UUID.randomUUID(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false),
                new StringDeserializer(), new StringDeserializer())) {
            List<TopicPartition> partitions = consumer.partitionsFor(TOPIC, Duration.ofSeconds(10)).stream()
                    .map(p -> new TopicPartition(TOPIC, p.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions, Duration.ofSeconds(10));
            while (partitions.stream().anyMatch(p -> consumer.position(p) < end.get(p))) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value() == null) {
                        continue;
                    }
                    SeatStatus s = json.readValue(record.value(), SeatStatus.class);
                    if (s.eventId() == eventId && s.section().equals(section)) {
                        sold.accept(s.seatId());
                    }
                }
            }
        }
    }

    @Override
    public void close() {
        producer.close();
    }
}
