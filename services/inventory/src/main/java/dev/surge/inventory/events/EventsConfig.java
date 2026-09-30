package dev.surge.inventory.events;

import dev.surge.inventory.config.InventoryProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class EventsConfig {

    @Bean
    SeatEventPublisher seatEventPublisher(InventoryProperties props, JsonMapper json, MeterRegistry meters) {
        if (props.kafkaBootstrap() == null || props.kafkaBootstrap().isBlank()) {
            var log = LoggerFactory.getLogger(SeatEventPublisher.class);
            log.warn("KAFKA_BOOTSTRAP not set: seat events are only logged");
            return events -> events.forEach(e -> log.debug("seat event {}", e));
        }
        return new KafkaSeatEventPublisher(props.kafkaBootstrap(), json, meters);
    }

    @Bean
    SoldLedger soldLedger(InventoryProperties props, JsonMapper json) {
        if (props.kafkaBootstrap() == null || props.kafkaBootstrap().isBlank()) {
            LoggerFactory.getLogger(SoldLedger.class).warn("KAFKA_BOOTSTRAP not set: sold seats are not durable");
            return SoldLedger.NONE;
        }
        return new KafkaSoldLedger(props.kafkaBootstrap(), json);
    }
}
