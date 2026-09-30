package dev.surge.order.web;

import java.util.Map;

import dev.surge.order.checkout.CheckoutService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Internal API; the gateway authenticates the caller and sets {@code X-User-Id}. */
@RestController
public class CheckoutController {

    public record CheckoutRequest(String holdId) {}

    private final CheckoutService checkout;

    public CheckoutController(CheckoutService checkout) {
        this.checkout = checkout;
    }

    @PostMapping("/checkout")
    public ResponseEntity<?> checkout(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) CheckoutRequest req) {
        if (key == null || key.isBlank() || key.length() > 255) {
            return ResponseEntity.badRequest().body(Map.of("error", "IDEMPOTENCY_KEY_REQUIRED"));
        }
        if (req == null || req.holdId() == null || req.holdId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "BAD_REQUEST"));
        }
        var reply = checkout.checkout(userId, key, req.holdId());
        var res = ResponseEntity.status(reply.status()).contentType(MediaType.APPLICATION_JSON);
        if (reply.replayed()) {
            res.header("Idempotent-Replayed", "true");
        }
        if (reply.retryLater()) {
            res.header("Retry-After", "1");
        }
        return res.body(reply.body());
    }
}
