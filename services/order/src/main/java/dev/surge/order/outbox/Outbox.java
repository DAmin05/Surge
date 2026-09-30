package dev.surge.order.outbox;

import java.util.HashMap;
import java.util.Map;

import dev.surge.contracts.events.OrderEvent;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes events in the caller's transaction, so an event exists if and only if the
 * state change that caused it committed. {@link OutboxRelay} publishes them.
 *
 * <p>Each row also carries the W3C trace context of the transaction that wrote it, so
 * the relay can publish inside that trace: one trace then follows a checkout through
 * the outbox, Kafka and Payment.
 */
@Component
public class Outbox {

    public static final String ORDER_EVENTS = "order-events";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public Outbox(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void append(OrderEvent event) {
        Map<String, String> headers = new HashMap<>();
        GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
                .inject(Context.current(), headers, Map::put);
        jdbc.sql("INSERT INTO outbox (aggregate_id, topic, payload, headers) VALUES (?, ?, ?::jsonb, ?::jsonb)")
                .params(Long.toString(event.orderId()), ORDER_EVENTS, json.writeValueAsString(event),
                        json.writeValueAsString(headers))
                .update();
    }
}
