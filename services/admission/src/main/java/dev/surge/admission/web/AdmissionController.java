package dev.surge.admission.web;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import dev.surge.admission.config.AdmissionProperties;
import dev.surge.admission.queue.WaitingRoom;
import dev.surge.admission.queue.WaitingRoom.Position;
import dev.surge.admission.tokens.TokenType;
import dev.surge.admission.tokens.Tokens;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Behind the gateway. {@code /session} is public; the queue endpoints trust the
 * gateway's {@code X-User-Id}, taken from a verified session token.
 */
@RestController
public class AdmissionController {

    public static final String COOKIE = "surge_session";

    private final Tokens tokens;
    private final WaitingRoom room;
    private final AdmissionProperties props;

    public AdmissionController(Tokens tokens, WaitingRoom room, AdmissionProperties props) {
        this.tokens = tokens;
        this.room = room;
        this.props = props;
    }

    /**
     * The anonymous identity is created here, never by the client: a client choosing its
     * own id could mint unlimited queue positions or pose as someone else. An existing
     * valid session is kept.
     */
    @PostMapping("/session")
    public ResponseEntity<Map<String, Object>> session(@CookieValue(value = COOKIE, required = false) String cookie) {
        var existing = cookie == null ? null : tokens.verify(TokenType.SESSION, cookie).orElse(null);
        if (existing != null) {
            return ResponseEntity.ok(Map.of("userId", existing.sub(), "expiresAt", existing.exp().toString()));
        }
        String userId = UUID.randomUUID().toString();
        Instant exp = Instant.now().plus(props.sessionLifetime());
        String token = tokens.issue(TokenType.SESSION, userId, null, exp);
        ResponseCookie c = ResponseCookie.from(COOKIE, token)
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(props.sessionLifetime())
                .build();
        return ResponseEntity.status(201).header(HttpHeaders.SET_COOKIE, c.toString())
                .body(Map.of("userId", userId, "expiresAt", exp.toString()));
    }

    @PostMapping("/queue/{eventId}/join")
    public Map<String, Object> join(@RequestHeader("X-User-Id") String userId, @PathVariable long eventId) {
        return body(room.join(userId, eventId));
    }

    @GetMapping("/queue/{eventId}/position")
    public ResponseEntity<Map<String, Object>> position(@RequestHeader("X-User-Id") String userId,
            @PathVariable long eventId) {
        Position p = room.position(userId, eventId);
        return p instanceof Position.NotInQueue
                ? ResponseEntity.status(404).body(Map.of("status", "NOT_IN_QUEUE"))
                : ResponseEntity.ok(body(p));
    }

    private static Map<String, Object> body(Position p) {
        return switch (p) {
            case Position.Waiting w -> Map.of("status", "WAITING", "position", w.position(), "ahead", w.ahead(),
                    "estimatedWaitSeconds", w.estimatedWaitSeconds());
            case Position.Admitted a -> Map.of("status", "ADMITTED", "token", a.token(),
                    "expiresAt", a.expiresAt().toString());
            case Position.NotInQueue n -> Map.of("status", "NOT_IN_QUEUE");
        };
    }
}
