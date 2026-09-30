package dev.surge.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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

/**
 * The running service end to end: HTTP checkout against real Postgres, the outbox relay
 * publishing to a real Redpanda, and a signed payment webhook driving the saga.
 * Inventory is faked.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class OrderApplicationTest {

    private static final String SECRET = "test-webhook-secret";

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        FakeInventory fakeInventory() {
            return new FakeInventory();
        }
    }

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestSupport.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "orders_app");
        registry.add("spring.datasource.password", () -> PostgresTestSupport.APP_PASSWORD);
        registry.add("surge.order.kafka-bootstrap", KafkaTestSupport::bootstrap);
        registry.add("surge.order.webhook-secret", () -> SECRET);
        // Other test classes share this database; their orders must not be swept here.
        registry.add("surge.order.timeout-sweep-interval", () -> "PT1H");
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

    private HttpResponse<String> checkout(String user, String holdId, String key) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/checkout"))
                .header("X-User-Id", user)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"holdId\":\"" + holdId + "\"}"));
        if (key != null) {
            req.header("Idempotency-Key", key);
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> checkout(String user, String holdId) throws Exception {
        return checkout(user, holdId, UUID.randomUUID().toString());
    }

    private HttpResponse<String> webhook(String body, String signature) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/payments/webhook"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (signature != null) {
            req.header("Surge-Signature", signature);
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void checkoutRelayAndWebhookCarryAnOrderToConfirmed() throws Exception {
        inventory.holds.put("h1", new FakeInventory.Hold("alice", event.eventId(), "A", event.seats("A").subList(0, 2)));

        var res = checkout("alice", "h1");
        assertThat(res.statusCode()).isEqualTo(201);
        assertThat(res.body()).contains("\"state\":\"PAYMENT_PENDING\"").contains("\"amountCents\":8400");
        long orderId = Long.parseLong(res.body().replaceAll(".*\"orderId\":(\\d+).*", "$1"));

        // The relay publishes the payment request.
        var requested = KafkaTestSupport.readUntil("order-events", Duration.ofSeconds(20),
                seen -> seen.stream().anyMatch(r -> r.key().equals(Long.toString(orderId))));
        var request = requested.stream().filter(r -> r.key().equals(Long.toString(orderId))).findFirst().orElseThrow();
        var payload = OrderFixtures.JSON.readTree(request.value());
        assertThat(payload.get("type").asString()).isEqualTo("PAYMENT_REQUESTED");
        String paymentKey = payload.get("paymentKey").asString();

        String body = "{\"paymentKey\":\"" + paymentKey + "\",\"status\":\"SUCCEEDED\",\"occurredAtMs\":1}";
        assertThat(webhook(body, null).statusCode()).isEqualTo(401);
        assertThat(webhook(body, "sha256=" + "00".repeat(32)).statusCode()).isEqualTo(401);
        var ok = webhook(body, sign(body));
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("CONFIRMED");
        assertThat(webhook(body, sign(body)).body()).contains("DUPLICATE");

        var confirmed = KafkaTestSupport.readUntil("order-events", Duration.ofSeconds(20),
                seen -> seen.stream().anyMatch(r -> r.key().equals(Long.toString(orderId))
                        && r.value().contains("ORDER_CONFIRMED")));
        var event = confirmed.stream().filter(r -> r.key().equals(Long.toString(orderId))
                        && r.value().contains("ORDER_CONFIRMED"))
                .map(r -> OrderFixtures.JSON.readTree(r.value())).findFirst().orElseThrow();
        assertThat(event.get("holdId").asString()).isEqualTo("h1");
        assertThat(event.get("seatIds")).hasSize(2);
    }

    @Test
    void checkoutRequiresAnIdempotencyKeyAndReplaysWithIt() throws Exception {
        inventory.holds.put("h2", new FakeInventory.Hold("bob", event.eventId(), "A", List.of(event.seats("A").get(2))));
        assertThat(checkout("bob", "h2", null).statusCode()).isEqualTo(400);

        String key = UUID.randomUUID().toString();
        var first = checkout("bob", "h2", key);
        var second = checkout("bob", "h2", key);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(second.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(inventory.pinned).containsOnlyOnce("h2");
    }

    @Test
    void losingTheClaimReleasesTheHoldAndReturnsConflict() throws Exception {
        long seat = event.seats("A").get(3);
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
        inventory.holds.put("h", new FakeInventory.Hold("alice", event.eventId(), "A", List.of(event.seats("A").get(0))));

        assertThat(checkout("mallory", "h").statusCode()).isEqualTo(403);
        assertThat(checkout("alice", "nope").statusCode()).isEqualTo(410);
        inventory.forcedRejection = InventoryClient.PinOutcome.Reason.REBUILDING;
        var retry = checkout("alice", "h");
        assertThat(retry.statusCode()).isEqualTo(503);
        assertThat(retry.headers().firstValue("Retry-After")).contains("1");
    }

    @Test
    void catalogListsSectionsPricesAndSeats() throws Exception {
        var res = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/events/" + event.eventId()))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
        var body = OrderFixtures.JSON.readTree(res.body());
        assertThat(body.get("sections")).hasSize(1);
        assertThat(body.get("sections").get(0).get("section").asString()).isEqualTo("A");
        assertThat(body.get("sections").get(0).get("priceCents").asLong()).isEqualTo(4200);
        assertThat(body.get("sections").get(0).get("seats")).hasSize(4);

        var missing = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/events/999999999"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(missing.statusCode()).isEqualTo(404);
    }

    @Test
    void healthIsUp() throws Exception {
        var res = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
    }
}
