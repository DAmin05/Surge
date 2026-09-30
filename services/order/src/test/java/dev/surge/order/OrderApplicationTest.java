package dev.surge.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import dev.surge.order.checkout.FakeInventory;
import dev.surge.order.inventory.InventoryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Checkout end to end over HTTP, against real Postgres, with Inventory faked. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class OrderApplicationTest {

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        FakeInventory fakeInventory() {
            return new FakeInventory();
        }
    }

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestSupport.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "orders_app");
        registry.add("spring.datasource.password", () -> PostgresTestSupport.APP_PASSWORD);
    }

    @LocalServerPort
    int port;

    @Autowired
    FakeInventory inventory;

    private final HttpClient http = HttpClient.newHttpClient();
    private PostgresTestSupport.Seeded event;

    @BeforeEach
    void seed() {
        inventory.holds.clear();
        inventory.released.clear();
        inventory.forcedRejection = null;
        event = PostgresTestSupport.seedEvent(Map.of("A", 4200L), 4);
    }

    private HttpResponse<String> checkout(String user, String holdId) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/checkout"))
                .header("X-User-Id", user)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"holdId\":\"" + holdId + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthIsUp() throws Exception {
        var res = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
    }

    @Test
    void happyPathReservesSeatsAndPricesTheOrder() throws Exception {
        List<Long> seats = event.seats("A").subList(0, 2);
        inventory.holds.put("h1", new FakeInventory.Hold("alice", event.eventId(), "A", seats));

        var res = checkout("alice", "h1");

        assertThat(res.statusCode()).isEqualTo(201);
        assertThat(res.body()).contains("\"state\":\"SEAT_RESERVED\"").contains("\"amountCents\":8400");
        assertThat(inventory.pinned).containsExactly("h1");
        assertThat(inventory.released).isEmpty();
    }

    @Test
    void losingTheClaimReleasesTheHoldAndReturnsConflict() throws Exception {
        long seat = event.seats("A").get(2);
        // Two holds on one seat: what a lost Redis write during failover looks like.
        inventory.holds.put("first", new FakeInventory.Hold("alice", event.eventId(), "A", List.of(seat)));
        inventory.holds.put("second", new FakeInventory.Hold("bob", event.eventId(), "A", List.of(seat)));

        assertThat(checkout("alice", "first").statusCode()).isEqualTo(201);
        var lost = checkout("bob", "second");

        assertThat(lost.statusCode()).isEqualTo(409);
        assertThat(lost.body()).contains("SEAT_TAKEN");
        assertThat(inventory.released).containsExactly("second");
    }

    @Test
    void holdProblemsMapToHttpStatuses() throws Exception {
        inventory.holds.put("h", new FakeInventory.Hold("alice", event.eventId(), "A", List.of(event.seats("A").get(3))));

        assertThat(checkout("mallory", "h").statusCode()).isEqualTo(403);
        assertThat(checkout("alice", "nope").statusCode()).isEqualTo(410);
        inventory.forcedRejection = InventoryClient.PinOutcome.Reason.REBUILDING;
        var retry = checkout("alice", "h");
        assertThat(retry.statusCode()).isEqualTo(503);
        assertThat(retry.headers().firstValue("Retry-After")).contains("1");
    }
}
