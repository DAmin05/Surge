package dev.surge.admission;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import dev.surge.admission.queue.WaitingRoom;
import dev.surge.admission.queue.WaitingRoom.Position;
import dev.surge.admission.tokens.TokenType;
import dev.surge.admission.tokens.Tokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

/** Session identity, the waiting room and tokens, against a real Redis and real keys. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AdmissionTest {

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    static final Path KEYS;
    static final AtomicLong EVENTS = new AtomicLong(500);

    static {
        REDIS.start();
        try {
            KEYS = Files.createTempDirectory("surge-keys");
            var p = new ProcessBuilder("sh", "../../scripts/keys.sh", KEYS.toString()).inheritIO().start();
            if (p.waitFor() != 0) {
                throw new IllegalStateException("keys.sh failed");
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("surge.admission.redis-nodes", () -> REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("surge.admission.keys-dir", KEYS::toString);
        r.add("surge.admission.admit-rate-per-sec", () -> 50);
        // Tests drive admission by hand.
        r.add("surge.admission.admit-interval", () -> "PT1H");
    }

    @LocalServerPort
    int port;

    @Autowired
    WaitingRoom room;

    @Autowired
    Tokens tokens;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void theServerCreatesTheIdentityAndKeepsAValidOne() throws Exception {
        var first = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/session"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(first.statusCode()).isEqualTo(201);
        String cookie = first.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(cookie).startsWith("surge_session=").contains("HttpOnly").contains("SameSite=Lax");
        String token = cookie.substring("surge_session=".length(), cookie.indexOf(';'));
        var claims = tokens.verify(TokenType.SESSION, token).orElseThrow();
        assertThat(first.body()).contains(claims.sub());

        var again = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/session"))
                .header("Cookie", "surge_session=" + token)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(again.body()).contains(claims.sub());

        var forged = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/session"))
                .header("Cookie", "surge_session=" + token.substring(0, token.length() - 4) + "AAAA")
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(forged.statusCode()).isEqualTo(201);
        assertThat(forged.body()).doesNotContain(claims.sub());
    }

    @Test
    void tokensAreTypedSoASessionCannotPassAsAnAdmission() {
        String session = tokens.issue(TokenType.SESSION, "u", null, Instant.now().plusSeconds(60));
        String admission = tokens.issue(TokenType.ADMISSION, "u", 7L, Instant.now().plusSeconds(60));

        assertThat(tokens.verify(TokenType.ADMISSION, session)).isEmpty();
        assertThat(tokens.verify(TokenType.SESSION, admission)).isEmpty();
        assertThat(tokens.verify(TokenType.ADMISSION, admission)).get()
                .satisfies(c -> assertThat(c.eventId()).isEqualTo(7L));
        assertThat(tokens.verify(TokenType.ADMISSION,
                tokens.issue(TokenType.ADMISSION, "u", 7L, Instant.now().minusSeconds(1)))).isEmpty();
    }

    @Test
    void oneQueuePositionPerUserInArrivalOrder() {
        long event = EVENTS.incrementAndGet();
        assertThat(room.join("alice", event)).isEqualTo(new Position.Waiting(1, 0, 0));
        assertThat(room.join("bob", event)).isEqualTo(new Position.Waiting(2, 1, 1));
        // Joining again doesn't move alice to the back or give her a second place.
        assertThat(room.join("alice", event)).isEqualTo(new Position.Waiting(1, 0, 0));
        assertThat(room.depth(event)).isEqualTo(2);
        assertThat(room.position("carol", event)).isEqualTo(new Position.NotInQueue());
    }

    @Test
    void admittedUsersGetAnEventBoundTokenThatExpiresTenMinutesAfterAdmission() {
        long event = EVENTS.incrementAndGet();
        room.join("alice", event);
        room.admitNext(event);

        var admitted = (Position.Admitted) room.position("alice", event);
        var claims = tokens.verify(TokenType.ADMISSION, admitted.token()).orElseThrow();
        assertThat(claims.sub()).isEqualTo("alice");
        assertThat(claims.eventId()).isEqualTo(event);
        assertThat(Duration.between(Instant.now(), claims.exp())).isBetween(Duration.ofMinutes(9), Duration.ofMinutes(10));
        // Polling again mints an equivalent token with the same expiry; joining again is a no-op.
        assertThat(((Position.Admitted) room.position("alice", event)).expiresAt()).isEqualTo(admitted.expiresAt());
        assertThat(room.join("alice", event)).isInstanceOf(Position.Admitted.class);
    }

    @Test
    void admissionRateIsGlobalAcrossConcurrentAdmitters() throws Exception {
        long event = EVENTS.incrementAndGet();
        for (int i = 0; i < 500; i++) {
            room.join("user-" + i, event);
        }
        // Burst = one second's worth (50). Eight "instances" hammering the admitter for
        // one second may admit at most the burst plus one second of refill.
        var admitted = new ConcurrentLinkedQueue<Long>();
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) {
                jobs.add(pool.submit(() -> {
                    while (System.nanoTime() < deadline) {
                        admitted.add(room.admitNext(event));
                    }
                }));
            }
            for (var j : jobs) {
                j.get();
            }
        }
        long total = admitted.stream().mapToLong(Long::longValue).sum();
        assertThat(total).isBetween(50L, 50L + 55L);
        assertThat(room.depth(event)).isEqualTo(500 - total);
        // First in, first out.
        assertThat(room.position("user-0", event)).isInstanceOf(Position.Admitted.class);
        assertThat(room.position("user-499", event)).isInstanceOf(Position.Waiting.class);
    }

    @Test
    void queueApiNeedsTheGatewayIdentity() throws Exception {
        long event = EVENTS.incrementAndGet();
        var noUser = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/queue/" + event + "/join"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(noUser.statusCode()).isEqualTo(400);

        var joined = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/queue/" + event + "/join"))
                .header("X-User-Id", "dave").POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(joined.body()).contains("\"status\":\"WAITING\"").contains("\"position\":1");
    }
}
