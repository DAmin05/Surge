package dev.surge.order;

import java.time.Duration;

import dev.surge.order.claim.ClaimService;
import dev.surge.order.config.OrderProperties;
import dev.surge.order.outbox.Outbox;
import dev.surge.order.saga.OrderStore;
import dev.surge.order.saga.PaymentOutcomes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Order components wired by hand against the shared test Postgres, as orders_app. */
public final class OrderFixtures {

    public static final JdbcClient JDBC = JdbcClient.create(PostgresTestSupport.APP);
    public static final TransactionTemplate TX =
            new TransactionTemplate(new DataSourceTransactionManager(PostgresTestSupport.APP));
    public static final JsonMapper JSON = JsonMapper.builder().build();
    public static final OrderStore ORDERS = new OrderStore(JDBC);
    public static final Outbox OUTBOX = new Outbox(JDBC, JSON);

    private OrderFixtures() {}

    public static ClaimService claims() {
        return new ClaimService(JDBC, TX, ORDERS, OUTBOX, new OrderProperties(4, "unused:0", Duration.ofSeconds(1)),
                new SimpleMeterRegistry());
    }

    public static PaymentOutcomes outcomes() {
        return new PaymentOutcomes(JDBC, TX, ORDERS, OUTBOX, new SimpleMeterRegistry());
    }

    public static long count(String sql, Object... params) {
        return JDBC.sql(sql).params(params).query(Long.class).single();
    }

    public static String string(String sql, Object... params) {
        return JDBC.sql(sql).params(params).query(String.class).single();
    }
}
