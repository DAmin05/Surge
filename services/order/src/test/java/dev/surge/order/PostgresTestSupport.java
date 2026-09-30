package dev.surge.order;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One Postgres for the whole test JVM, migrated exactly like the Compose stack: the
 * admin bootstrap (roles, schemas, grants), then the orders migrations as
 * orders_owner. The service under test connects as orders_app, so missing grants
 * fail tests just as they would in production.
 */
public final class PostgresTestSupport {

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("surge")
            .withUsername("surge_admin")
            .withPassword("admin")
            .withCommand("postgres", "-c", "max_connections=300");

    public static final String APP_PASSWORD = "orders_app_test";
    private static final String OWNER_PASSWORD = "orders_owner_test";

    public static final DataSource APP;
    public static final DataSource OWNER;

    static {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:../../infra/postgres/bootstrap")
                .table("flyway_bootstrap_history")
                .placeholders(Map.of(
                        "orders_owner_password", OWNER_PASSWORD,
                        "orders_app_password", APP_PASSWORD,
                        "payments_owner_password", "x",
                        "payments_app_password", "x",
                        "reconciler_password", "x"))
                .load().migrate();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), "orders_owner", OWNER_PASSWORD)
                .schemas("orders")
                .createSchemas(false)
                .locations("filesystem:db/migration")
                .load().migrate();
        APP = pool("orders_app", APP_PASSWORD, 20);
        OWNER = pool("orders_owner", OWNER_PASSWORD, 2);
    }

    private PostgresTestSupport() {}

    private static DataSource pool(String user, String password, int size) {
        var cfg = new HikariConfig();
        cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(size);
        return new HikariDataSource(cfg);
    }

    /** Seeded event: sections with prices, seats numbered 1..seatsPerSection per section. */
    public record Seeded(long eventId, Map<String, List<Long>> seats) {
        public List<Long> seats(String section) {
            return seats.get(section);
        }
    }

    /** Inserts a fresh event (as the owner) so tests never share seats. */
    public static Seeded seedEvent(Map<String, Long> sectionPrices, int seatsPerSection) {
        var jdbc = JdbcClient.create(OWNER);
        long eventId = jdbc.sql("""
                INSERT INTO events (name, starts_at, capacity) VALUES ('test', now(), ?) RETURNING id""")
                .param(sectionPrices.size() * seatsPerSection)
                .query(Long.class).single();
        var seats = new java.util.TreeMap<String, List<Long>>();
        sectionPrices.forEach((section, price) -> {
            jdbc.sql("INSERT INTO section_prices (event_id, section, price_cents) VALUES (?, ?, ?)")
                    .params(eventId, section, price).update();
            var ids = new ArrayList<Long>();
            for (int n = 1; n <= seatsPerSection; n++) {
                ids.add(jdbc.sql("""
                        INSERT INTO seats (event_id, section, row_label, seat_number)
                        VALUES (?, ?, 'A', ?) RETURNING id""")
                        .params(eventId, section, n)
                        .query(Long.class).single());
            }
            seats.put(section, ids);
        });
        return new Seeded(eventId, seats);
    }
}
