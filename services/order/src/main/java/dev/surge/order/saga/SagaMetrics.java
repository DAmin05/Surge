package dev.surge.order.saga;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code orders_by_state{state}}: how many orders sit in each saga state right now, for
 * the war room. Counted every few seconds rather than on every scrape.
 */
@Component
public class SagaMetrics {

    private final JdbcClient jdbc;
    private final Map<OrderState, AtomicLong> counts = new EnumMap<>(OrderState.class);

    public SagaMetrics(JdbcClient jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        for (OrderState state : OrderState.values()) {
            var value = new AtomicLong();
            counts.put(state, value);
            Gauge.builder("orders_by_state", value, AtomicLong::get).tag("state", state.name()).register(meters);
        }
    }

    @Scheduled(fixedDelayString = "PT5S")
    public void refresh() {
        try {
            var seen = new EnumMap<OrderState, Long>(OrderState.class);
            jdbc.sql("SELECT state, count(*) FROM orders GROUP BY state")
                    .query(rs -> {
                        seen.put(OrderState.valueOf(rs.getString(1)), rs.getLong(2));
                    });
            counts.forEach((state, value) -> value.set(seen.getOrDefault(state, 0L)));
        } catch (RuntimeException e) {
            // Database unavailable (e.g. a chaos partition): keep the last known values.
        }
    }
}
