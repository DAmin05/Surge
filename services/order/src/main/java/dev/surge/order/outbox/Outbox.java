package dev.surge.order.outbox;

import dev.surge.contracts.events.OrderEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes events in the caller's transaction, so an event exists if and only if the
 * state change that caused it committed. {@link OutboxRelay} publishes them.
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
        jdbc.sql("INSERT INTO outbox (aggregate_id, topic, payload) VALUES (?, ?, ?::jsonb)")
                .params(Long.toString(event.orderId()), ORDER_EVENTS, json.writeValueAsString(event))
                .update();
    }
}
