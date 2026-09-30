package dev.surge.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class InventoryApplicationTest {

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("surge.inventory.redis-nodes", RedisTestSupport::node);
        registry.add("surge.inventory.grpc-port", () -> 0);
        // This context outlives the test class and shares Redis with HoldServiceTest,
        // whose sweeper tests must be the only sweeper running.
        registry.add("surge.inventory.sweep-interval", () -> "PT1H");
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(HttpRequest.Builder req) throws Exception {
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder hold(String user, String body) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/holds"))
                .header("X-User-Id", user)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    @Test
    void healthIsUp() throws Exception {
        var res = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")));
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"UP\"");
    }

    @Test
    void holdConflictAndRelease() throws Exception {
        String body = "{\"eventId\":7001,\"section\":\"A\",\"seatIds\":[3,4]}";

        var held = send(hold("alice", body));
        assertThat(held.statusCode()).isEqualTo(201);
        assertThat(held.body()).contains("\"holdId\":\"7001:A:");

        var conflict = send(hold("bob", "{\"eventId\":7001,\"section\":\"A\",\"seatIds\":[4]}"));
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.body()).contains("SEAT_TAKEN").contains("\"seatId\":4");

        var bad = send(hold("bob", "{\"eventId\":7001,\"section\":\"A\",\"seatIds\":[1,2,3,4,5]}"));
        assertThat(bad.statusCode()).isEqualTo(400);

        String holdId = held.body().replaceAll(".*\"holdId\":\"([^\"]+)\".*", "$1");
        var released = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/holds/" + holdId))
                .header("X-User-Id", "alice").DELETE());
        assertThat(released.statusCode()).isEqualTo(204);

        var snapshot = send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/sections/7001/A/snapshot")));
        assertThat(snapshot.body()).contains("\"held\":[]").contains("\"seq\":4");
    }
}
